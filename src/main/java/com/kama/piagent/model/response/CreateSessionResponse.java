package com.kama.piagent.model.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreateSessionResponse {
    private String sessionId;
}
