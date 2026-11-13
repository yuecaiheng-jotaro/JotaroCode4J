package org.jotaro.jotarocode.agent;

import org.jotaro.jotarocode.agent.runtime.AgentTool;
import org.jotaro.jotarocode.agent.runtime.CallbackAgentTool;
import org.jotaro.jotarocode.agent.runtime.SpringAiAssistantStreamer;
import org.jotaro.jotarocode.agent.runtime.ToolExecutionMode;
import org.jotaro.jotarocode.agent.tools.SubagentTool;
import org.jotaro.jotarocode.agent.tools.ToolContributor;
import org.jotaro.jotarocode.config.ChatClientRegistry;
import org.jotaro.jotarocode.config.PiAgentProperties;
import org.jotaro.jotarocode.exception.BizException;
import org.jotaro.jotarocode.skill.SkillCatalog;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 按一次会话的需要组装 {@link PiAgent}：选模型、拼系统提示、把 {@code ToolContributor} 适配成循环认识的 {@code AgentTool}。
 * 技能目录只在创建时读一次，之后换模型不会重新扫描技能。
 */
@Component
public class PiAgentFactory {

    @Resource
    private ChatClientRegistry chatClientRegistry;
    @Resource
    private List<ToolContributor> contributors;
    @Resource
    private PiAgentProperties properties;
    @Resource
    private SkillCatalog skillCatalog;

    private List<AgentTool> tools = List.of();

    @PostConstruct
    void registerTools() {
        this.tools = adaptTools(contributors);
        if (contributors == null) {
            return;
        }
        for (ToolContributor contributor : contributors) {
            if (contributor instanceof SubagentTool subagentTool) {
                subagentTool.bind(tools);
            }
        }
    }

    public PiAgent create(String model, String systemPrompt) {
        String resolvedModel = StringUtils.hasText(model) ? model : properties.getModel();
        String resolvedPrompt = StringUtils.hasText(systemPrompt) ? systemPrompt : properties.getSystemPrompt();
        String skills = skillCatalog.promptSection();
        if (StringUtils.hasText(skills)) {
            resolvedPrompt = resolvedPrompt + "\n\n" + skills;
        }
        ChatClient chatClient = chatClientRegistry.get(resolvedModel);
        if (chatClient == null) {
            throw new BizException("未找到模型: " + resolvedModel);
        }
        PiAgent agent = new PiAgent(
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

    public List<String> modelNames() {
        return chatClientRegistry.keys().stream().sorted().toList();
    }

    public String defaultModel() {
        return properties.getModel();
    }

    public void switchModel(PiAgent agent, String model) {
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
