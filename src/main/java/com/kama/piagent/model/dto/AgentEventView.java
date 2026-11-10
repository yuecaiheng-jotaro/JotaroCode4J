package com.kama.piagent.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AgentEventView {
    private String type;
    private String text;
    private String toolName;
    private String arguments;
    private String result;
    private Boolean error;
    private Boolean done;
}
