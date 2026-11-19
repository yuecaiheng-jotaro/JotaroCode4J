package org.jotaro.jotarocode.agent.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jotaro.jotarocode.config.JotaroAgentProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 双层上下文事件日志存储（Redis 热缓存 + SQLite 长期持久化）。
 * <p>
 * 写入时同时落盘到 {@link SqliteContextStore}（{@code .jotaro/jotaro.db}）和 Redis List（{@code jotaro:ctx:<sessionId>:events}，默认 TTL 24h）；
 * 读取时优先命中 Redis，缓存未命中（如进程重启或 {@code /resume} 恢复历史会话）时自动从 SQLite 加载并回填 Redis；
 * 若本地 Redis 临时未启动，平滑降级为纯 SQLite，不中断 Agent 运行。
 */
@Slf4j
@Primary
@Component
public class RedisContextStore implements ContextStore {

    @Resource
    private ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    @Resource
    private SqliteContextStore sqliteContextStore;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private JotaroAgentProperties properties;

    private final InMemoryContextStore memoryMirror = new InMemoryContextStore();
    private final AtomicBoolean redisDegraded = new AtomicBoolean(false);

    @Override
    public void append(String sessionId, ContextEvent event) {
        if (sessionId == null || event == null) {
            return;
        }
        memoryMirror.append(sessionId, event);
        if (sqliteContextStore != null) {
            sqliteContextStore.append(sessionId, event);
        }
        StringRedisTemplate redis = activeRedis();
        if (redis == null) {
            return;
        }
        try {
            String key = keyOf(sessionId);
            String json = objectMapper.writeValueAsString(event);
            redis.opsForList().rightPush(key, json);
            redis.expire(key, ttl());
            redisDegraded.set(false);
        } catch (JsonProcessingException exception) {
            log.warn("序列化 ContextEvent 失败: {}", exception.getMessage());
        } catch (RuntimeException exception) {
            markDegraded(exception);
        }
    }

    @Override
    public List<ContextEvent> events(String sessionId) {
        if (sessionId == null) {
            return List.of();
        }
        StringRedisTemplate redis = activeRedis();
        if (redis != null) {
            try {
                List<String> raw = redis.opsForList().range(keyOf(sessionId), 0, -1);
                if (raw != null && !raw.isEmpty()) {
                    List<ContextEvent> parsed = new ArrayList<>(raw.size());
                    for (String item : raw) {
                        parsed.add(objectMapper.readValue(item, ContextEvent.class));
                    }
                    redisDegraded.set(false);
                    return List.copyOf(parsed);
                }
            } catch (Exception exception) {
                markDegraded(exception);
            }
        }
        // Redis 未命中或不可用时，从 SQLite 恢复并回填 Redis 缓存
        if (sqliteContextStore != null) {
            List<ContextEvent> persisted = sqliteContextStore.events(sessionId);
            if (!persisted.isEmpty()) {
                warmRedis(sessionId, persisted);
                return persisted;
            }
        }
        return memoryMirror.events(sessionId);
    }

    @Override
    public void clear(String sessionId) {
        if (sessionId == null) {
            return;
        }
        memoryMirror.clear(sessionId);
        if (sqliteContextStore != null) {
            sqliteContextStore.clear(sessionId);
        }
        StringRedisTemplate redis = activeRedis();
        if (redis == null) {
            return;
        }
        try {
            redis.delete(keyOf(sessionId));
            redisDegraded.set(false);
        } catch (RuntimeException exception) {
            markDegraded(exception);
        }
    }

    @Override
    public List<SessionSummary> listSessions(int limit) {
        if (sqliteContextStore != null) {
            List<SessionSummary> persisted = sqliteContextStore.listSessions(limit);
            if (!persisted.isEmpty()) {
                return persisted;
            }
        }
        return memoryMirror.listSessions(limit);
    }

    @Override
    public String storageKind() {
        boolean redisOk = activeRedis() != null && !redisDegraded.get();
        if (redisOk && sqliteContextStore != null) {
            return "redis+sqlite";
        }
        if (sqliteContextStore != null) {
            return "sqlite";
        }
        return redisOk ? "redis" : "memory";
    }

    private void warmRedis(String sessionId, List<ContextEvent> events) {
        StringRedisTemplate redis = activeRedis();
        if (redis == null || events.isEmpty()) {
            return;
        }
        try {
            String key = keyOf(sessionId);
            List<String> serialized = new ArrayList<>(events.size());
            for (ContextEvent event : events) {
                serialized.add(objectMapper.writeValueAsString(event));
            }
            redis.delete(key);
            redis.opsForList().rightPushAll(key, serialized);
            redis.expire(key, ttl());
            redisDegraded.set(false);
        } catch (Exception exception) {
            markDegraded(exception);
        }
    }

    private StringRedisTemplate activeRedis() {
        if (properties != null && properties.getContext() != null && !properties.getContext().isRedisEnabled()) {
            return null;
        }
        return redisTemplateProvider == null ? null : redisTemplateProvider.getIfAvailable();
    }

    private String keyOf(String sessionId) {
        String prefix = properties != null && properties.getContext() != null
                && StringUtils.hasText(properties.getContext().getKeyPrefix())
                ? properties.getContext().getKeyPrefix().trim()
                : "jotaro:ctx:";
        return prefix + sessionId + ":events";
    }

    private Duration ttl() {
        int hours = properties != null && properties.getContext() != null && properties.getContext().getTtlHours() > 0
                ? properties.getContext().getTtlHours()
                : 24;
        return Duration.ofHours(hours);
    }

    private void markDegraded(Exception exception) {
        if (redisDegraded.compareAndSet(false, true)) {
            log.warn("Redis 缓存不可用，已自动降级为 SQLite 持久化存储: {}", exception.getMessage());
        }
    }
}
