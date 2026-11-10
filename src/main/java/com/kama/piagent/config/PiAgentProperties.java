package com.kama.piagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "piagent")
public class PiAgentProperties {
    private String mode = "server";
    private String model = "deepseek-chat";
    private int maxSteps = 20;
    private int messageWindow = 40;
    private String systemPrompt = "你是 Pi Agent。任务完成后调用 terminate。";
}
