package org.jotaro.jotarocode.agent.context;

import java.util.List;

/**
 * 会话上下文事件日志持久化存储抽象。
 * <p>
 * 默认由 {@link RedisContextStore} 写入本地 Redis；当 Redis 不可用或处于纯单元测试/子代理环境时，
 * 自动使用 {@link InMemoryContextStore} 兜底。
 */
public interface ContextStore {

    /**
     * 向指定会话的事件日志末尾追加一条不可变事件。
     */
    void append(String sessionId, ContextEvent event);

    /**
     * 读取指定会话的完整事件日志（按追加顺序）。
     */
    List<ContextEvent> events(String sessionId);

    /**
     * 清空指定会话的事件日志（用于 {@code /reset}）。
     */
    void clear(String sessionId);

    /**
     * 返回当前实际生效的存储介质标识（{@code "redis"} 或 {@code "memory"}）。
     */
    String storageKind();
}
