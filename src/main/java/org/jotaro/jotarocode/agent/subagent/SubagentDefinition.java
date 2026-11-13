package org.jotaro.jotarocode.agent.subagent;

import java.util.List;

/**
 * 一个子代理的角色定义。{@code tools} 为空表示除 {@code subagent} 以外的全部工具。
 * {@code model} 为空表示跟随父会话当前模型。
 */
public record SubagentDefinition(
        String name,
        String description,
        List<String> tools,
        String model,
        String systemPrompt
) {
}
