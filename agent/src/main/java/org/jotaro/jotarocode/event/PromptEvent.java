package org.jotaro.jotarocode.event;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 一条待异步执行的会话用户消息事件。
 * <p>
 * 由 {@link org.jotaro.jotarocode.service.impl.AgentSessionServiceImpl} 在校验参数并展开 {@code /skill:} 后发布，
 * 交由 {@link org.jotaro.jotarocode.event.listener.PromptEventListener} 在异步线程池中消费。
 * 当会话正在运行时：{@code followUp=false} 会调用 {@code agent.prompt(...)} 作为 steering 消息在当前工具轮后立刻穿插；
 * {@code followUp=true} 则调用 {@code agent.followUp(...)} 等到本轮循环即将结束时再追加执行。
 */
@Data
@AllArgsConstructor
public class PromptEvent {
    /** 目标会话 ID。 */
    private String sessionId;
    /** 经过技能展开后的用户消息文本。 */
    private String content;
    /** 是否作为本轮结束后的追加消息（{@code false} 为普通启动或运行中 steering 穿插，{@code true} 为 follow-up）。 */
    private boolean followUp;
}
