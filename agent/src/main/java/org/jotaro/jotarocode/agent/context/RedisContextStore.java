package org.jotaro.jotarocode.agent.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jotaro.jotarocode.config.JotaroAgentProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 Redis List 的会话上下文事件日志存储。
 * <p>
 * 每个会话以 {@code jotaro:ctx:<sessionId>:events} 为 Key，使用 {@code RPUSH} 追加 JSON 化的 {@link ContextEvent}，
 * 并自动续期 TTL（默认 24 小时）。若本地 Redis 临时未启动或连接异常，会平滑降级到 {@link InMemoryContextStore}，
 * 保证 Agent 正常对话不报错。
 */
@Slf4j
@Component
public class RedisContextStore implements ContextStore {

    @Resource
    private ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private JotaroAgentProperties properties;

    private final InMemoryContextStore fallback = new InMemoryContextStore();
    private final AtomicBoolean redisDegraded = new AtomicBoolean(false);

    @Override
    public void append(String sessionId, ContextEvent event) {
        if (sessionId == null || event == null) {
            return;
        }
        // 同时写入本地镜像，确保 Redis 抖动时上下文不丢失
        fallback.append(sessionId, event);
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
        if (redis == null) {
            return fallback.events(sessionId);
        }
        try {
            List<String> raw = redis.opsForList().range(keyOf(sessionId), 0, -1);
            if (raw == null || raw.isEmpty()) {
                return fallback.events(sessionId);
            }
            List<ContextEvent> parsed = new ArrayList<>(raw.size());
            for (String item : raw) {
                parsed.add(objectMapper.readValue(item, ContextEvent.class));
            }
            redisDegraded.set(false);
            return List.copyOf(parsed);
        } catch (Exception exception) {
            markDegraded(exception);
            return fallback.events(sessionId);
        }
    }

    @Override
    public void clear(String sessionId) {
        if (sessionId == null) {
            return;
        }
        fallback.clear(sessionId);
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
    public String storageKind() {
        return activeRedis() != null && !redisDegraded.get() ? "redis" : "memory";
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
            log.warn("Redis 上下文存储不可用，已自动降级为内存存储: {}", exception.getMessage());
        }
    }
}
