package org.jotaro.jotarocode.model.dto;

import lombok.Builder;
import lombok.Data;

/**
 * SSE 的一条 JSON。{@code text} 在 MESSAGE_UPDATE 时是增量，在 MESSAGE_END 时是整段原文。
 * 页面如果已经拼过增量，就不能再把 MESSAGE_END 的 text 追加一次。
 * {@code done} 只在 AGENT_END 时为真。
 */
@Data
@Builder
public class AgentEventView {
    private String type;
    private String role;
    private String text;
    private String toolCallId;
    private String toolName;
    private String arguments;
    private String result;
    private Boolean error;
    private Boolean done;
    private boolean stepLimitReached;
}
