package org.jotaro.jotarocode.agent.runtime;

import lombok.Builder;
import lombok.Value;

/** {@code afterToolCall} 钩子的返回值。非空字段覆盖工具的原文、是否算错误、以及要不要结束循环。 */
@Value
@Builder
public class AfterToolCallResult {
    String content;
    Boolean error;
    Boolean terminate;
}
