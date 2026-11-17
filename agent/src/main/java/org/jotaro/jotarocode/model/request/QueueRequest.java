package org.jotaro.jotarocode.model.request;

import lombok.Data;

/**
 * 会话运行期间显式插入队列消息的请求体（{@code POST /api/sessions/{sessionId}/queue}）。
 * <p>
 * 支持两种插入时机：
 * <ul>
 *   <li>{@link QueueKind#STEER}：在当前工具执行完毕后立即穿插进对话，引导或修正正在进行的任务。</li>
 *   <li>{@link QueueKind#FOLLOW_UP}：等到当前整轮循环即将停止时再追加为一轮新输入。</li>
 * </ul>
 */
@Data
public class QueueRequest {
    /** 待插入的用户消息文本。 */
    private String content;
    /** 消息插入队列的方式（默认按 {@code STEER} 处理，为 {@code FOLLOW_UP} 时延后至本轮末尾）。 */
    private QueueKind kind;

    /**
     * 运行期消息插队模式枚举。
     */
    public enum QueueKind {
        /** 在当前步骤结束后立即穿插引导。 */
        STEER,
        /** 等到当前轮即将结束时再追加执行。 */
        FOLLOW_UP
    }
}
