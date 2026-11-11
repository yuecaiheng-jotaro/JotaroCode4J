package org.jotaro.jotarocode.event;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 一条待执行的用户消息。{@code followUp=false} 时运行中会变成 steering，
 * {@code true} 时等到本轮快结束再插入。
 */
@Data
@AllArgsConstructor
public class PromptEvent {
    private String sessionId;
    private String content;
    private boolean followUp;
}
