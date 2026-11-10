package com.kama.piagent.model.request;

import lombok.Data;

@Data
public class CreateSessionRequest {
    private String model;
    private String systemPrompt;
}
