package org.jotaro.jotarocode.agent.subagent;

import java.util.List;

/**
 * 子代理（Subagent）的静态角色定义。
 *
 * <p>由 {@link SubagentCatalog} 从内置列表或 {@code .jotaro/agents/*.md} 文件解析生成，
 * 供 {@link SubagentRunner} 在派生子代理实例时决定其系统提示词、可用工具子集以及使用的大模型。
 *
 * @param name         子代理唯一标识名（kebab-case，如 {@code explore}、{@code general}）
 * @param description  简短职责说明，会拼入 {@code subagent} 工具的描述中供父模型选择角色时参考
 * @param tools        允许该子代理调用的工具名称白名单；为 {@code null} 时表示允许除 {@code subagent} 以外的全部父工具
 * @param model        指定该子代理使用的模型名；为 {@code null} 时自动跟随父会话当前正在使用的模型
 * @param systemPrompt 子代理专属的指令正文
 */
public record SubagentDefinition(
        String name,
        String description,
        List<String> tools,
        String model,
        String systemPrompt
) {
}
