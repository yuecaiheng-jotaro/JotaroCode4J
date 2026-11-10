package com.kama.piagent.agent.runtime;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class BeforeToolCallResult {
    boolean block;
    String reason;
    boolean terminate;
}
