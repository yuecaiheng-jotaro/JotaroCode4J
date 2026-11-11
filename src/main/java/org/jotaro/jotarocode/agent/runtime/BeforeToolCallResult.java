package org.jotaro.jotarocode.agent.runtime;

import lombok.Builder;
import lombok.Value;

/** {@code beforeToolCall} 钩子的返回值。{@code block} 为真时不执行工具，把 {@code reason} 当作工具结果。 */
@Value
@Builder
public class BeforeToolCallResult {
    boolean block;
    String reason;
    boolean terminate;
}
