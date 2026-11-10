package com.kama.piagent.event;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PromptEvent {
    private String sessionId;
    private String content;
    private boolean followUp;
}
