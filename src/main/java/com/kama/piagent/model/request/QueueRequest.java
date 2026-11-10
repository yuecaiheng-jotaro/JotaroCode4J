package com.kama.piagent.model.request;

import lombok.Data;

@Data
public class QueueRequest {
    private String content;
    private QueueKind kind;

    public enum QueueKind {
        STEER,
        FOLLOW_UP
    }
}
