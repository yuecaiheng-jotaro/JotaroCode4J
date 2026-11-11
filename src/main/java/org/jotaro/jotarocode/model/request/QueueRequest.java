package org.jotaro.jotarocode.model.request;

import lombok.Data;

/**
 * 运行中插入消息。{@code STEER} 在当前轮结束后进入对话，{@code FOLLOW_UP} 等到循环即将停止。
 * 页面目前只用普通 messages 接口，这个接口留给需要区分两种插入的客户端。
 */
@Data
public class QueueRequest {
    private String content;
    private QueueKind kind;

    public enum QueueKind {
        STEER,
        FOLLOW_UP
    }
}
