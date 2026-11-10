package com.kama.piagent.agent.runtime;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AfterToolCallResult {
    String content;
    Boolean error;
    Boolean terminate;
}
