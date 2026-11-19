package org.jotaro.jotarocode.agent.context;

import java.util.List;

/**
 * 会话上下文事件日志持久化存储抽象。
 * <p>
 * 支持 Redis 热缓存 + SQLite（{@code .jotaro/jotaro.db}）持久化双写存储，
 * 并提供历史会话列表查询，以支撑 {@code /resume} 跨重启恢复历史上下文。
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
     * 按最近更新时间倒序列出可恢复的历史会话摘要（默认仅返回包含至少 1 条用户消息的会话）。
     *
     * @param limit 最多返回的会话数量
     * @return 历史会话摘要列表
     */
    default List<SessionSummary> listSessions(int limit) {
        return List.of();
    }

    /**
     * 返回当前实际生效的存储介质标识（如 {@code "redis+sqlite"}、{@code "sqlite"}、{@code "memory"}）。
     */
    String storageKind();

    /**
     * 历史会话摘要信息（供 {@code /resume} 展示与选择）。
     *
     * @param sessionId  会话唯一 ID
     * @param title      会话标题（默认取首条用户输入摘要）
     * @param updatedAt  最后活跃时间戳（毫秒）
     * @param eventCount 该会话记录的总事件条数
     */
    record SessionSummary(
            String sessionId,
            String title,
            long updatedAt,
            int eventCount
    ) {
    }
}
