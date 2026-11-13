package org.jotaro.jotarocode.agent;

import org.jotaro.jotarocode.agent.rag.RagBases;
import org.jotaro.jotarocode.agent.runtime.AgentTool;
import org.jotaro.jotarocode.agent.runtime.CallbackAgentTool;
import org.jotaro.jotarocode.agent.runtime.SpringAiAssistantStreamer;
import org.jotaro.jotarocode.agent.runtime.ToolExecutionMode;
import org.jotaro.jotarocode.agent.tools.SubagentTool;
import org.jotaro.jotarocode.agent.tools.ToolContributor;
import org.jotaro.jotarocode.config.ChatClientRegistry;
import org.jotaro.jotarocode.config.JotaroAgentProperties;
import org.jotaro.jotarocode.exception.BizException;
import org.jotaro.jotarocode.mcp.McpToolCatalog;
import org.jotaro.jotarocode.skill.SkillCatalog;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 按一次会话的需要组装 {@link JotaroAgent}：选模型、拼系统提示、把 {@code ToolContributor} 适配成循环认识的 {@code AgentTool}。
 * 技能目录只在创建时读一次，之后换模型不会重新扫描技能。
 */
@Slf4j
@Component
public class JotaroAgentFactory {

    @Resource
    private ChatClientRegistry chatClientRegistry;
    @Resource
    private List<ToolContributor> contributors;
    @Resource
    private JotaroAgentProperties properties;
    @Resource
    private SkillCatalog skillCatalog;
    @Resource
    private McpToolCatalog mcpToolCatalog;

    private List<AgentTool> localTools = List.of();
    private List<AgentTool> tools = List.of();

    @PostConstruct
    void registerTools() {
        this.localTools = adaptTools(contributors);
        if (!RagBases.enabled(properties.getRag() == null ? List.of() : properties.getRag().getBases())) {
            this.localTools = localTools.stream().filter(tool -> !"rag".equals(tool.name())).toList();
        }
        this.tools = merge(localTools, mcpToolCatalog.callbacks());
        if (contributors == null) {
            return;
        }
        for (ToolContributor contributor : contributors) {
            if (contributor instanceof SubagentTool subagentTool) {
                subagentTool.bind(tools);
            }
        }
    }

    /** 启动时从 MCP 服务发现、且没有和内置工具重名的工具。 */
    public Set<String> mcpToolNames() {
        Set<String> local = new LinkedHashSet<>();
        for (AgentTool tool : localTools) {
            local.add(tool.name());
        }
        Set<String> names = new LinkedHashSet<>();
        for (AgentTool tool : tools) {
            if (!local.contains(tool.name())) {
                names.add(tool.name());
            }
        }
        return names;
    }

    public JotaroAgent create(String model, String systemPrompt) {
        String resolvedModel = StringUtils.hasText(model) ? model : properties.getModel();
        String resolvedPrompt = StringUtils.hasText(systemPrompt) ? systemPrompt : properties.getSystemPrompt();
        String skills = skillCatalog.promptSection();
        if (StringUtils.hasText(skills)) {
            resolvedPrompt = resolvedPrompt + "\n\n" + skills;
        }
        if (!mcpToolNames().isEmpty()) {
            resolvedPrompt = resolvedPrompt + "\n\n已连接的 MCP 工具也可以调用。工具名以服务名开头，参数按其说明填写。";
        }
        String bases = RagBases.prompt(properties.getRag().getBases());
        if (StringUtils.hasText(bases)) {
            resolvedPrompt = resolvedPrompt + "\n\n" + bases;
        }
        ChatClient chatClient = chatClientRegistry.get(resolvedModel);
        if (chatClient == null) {
            throw new BizException("未找到模型: " + resolvedModel);
        }
        JotaroAgent agent = new JotaroAgent(
                resolvedPrompt,
                tools,
                new SpringAiAssistantStreamer(chatClient),
                properties.getMaxSteps(),
                properties.getMessageWindow(),
                ToolExecutionMode.PARALLEL
        );
        agent.useModel(resolvedModel);
        return agent;
    }

    /**
     * 关掉 Spring AI 的自动执行后，模型仍然需要 {@code ToolCallback} 才能看到参数定义。
     * 这里保留 callback 供模型声明使用，真正调用发生在循环里的 {@code callback.call}。
     */
    private static List<AgentTool> adaptTools(List<ToolContributor> contributors) {
        if (contributors == null || contributors.isEmpty()) {
            return List.of();
        }
        // ToolCallbacks 去扫@Tool的方法，装载成ToolCallback
        ToolCallback[] callbacks = ToolCallbacks.from(contributors.toArray());
        List<AgentTool> agentTools = new ArrayList<>();
        for (ToolCallback callback : callbacks) {
            agentTools.add(new CallbackAgentTool(callback));
        }
        return agentTools;
    }

    static List<AgentTool> merge(List<AgentTool> local, List<ToolCallback> remote) {
        LinkedHashMap<String, AgentTool> tools = new LinkedHashMap<>();
        if (local != null) {
            for (AgentTool tool : local) {
                tools.put(tool.name(), tool);
            }
        }
        if (remote == null) {
            return List.copyOf(tools.values());
        }
        for (ToolCallback callback : remote) {
            if (callback == null || callback.getToolDefinition() == null) {
                continue;
            }
            AgentTool tool = new CallbackAgentTool(callback);
            if (tools.containsKey(tool.name())) {
                log.warn("MCP 工具 {} 与已有工具重名，已跳过", tool.name());
                continue;
            }
            tools.put(tool.name(), tool);
        }
        return List.copyOf(tools.values());
    }

    public List<String> modelNames() {
        return chatClientRegistry.keys().stream().sorted().toList();
    }

    public String defaultModel() {
        return properties.getModel();
    }

    public void switchModel(JotaroAgent agent, String model) {
        String resolvedModel = StringUtils.hasText(model) ? model : properties.getModel();
        ChatClient chatClient = chatClientRegistry.get(resolvedModel);
        if (chatClient == null) {
            throw new BizException("未找到模型: " + resolvedModel);
        }
        try {
            agent.useStreamer(new SpringAiAssistantStreamer(chatClient));
            agent.useModel(resolvedModel);
        } catch (IllegalStateException exception) {
            throw new BizException(exception.getMessage());
        }
    }
}
