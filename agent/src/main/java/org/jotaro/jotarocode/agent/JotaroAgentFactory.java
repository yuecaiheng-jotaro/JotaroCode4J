package org.jotaro.jotarocode.agent;

import org.jotaro.jotarocode.agent.rag.RagBases;
import org.jotaro.jotarocode.agent.runtime.AgentTool;
import org.jotaro.jotarocode.agent.runtime.CallbackAgentTool;
import org.jotaro.jotarocode.agent.runtime.ContextCompactor;
import org.jotaro.jotarocode.agent.runtime.SpringAiAssistantStreamer;
import org.jotaro.jotarocode.agent.runtime.ToolExecutionMode;
import org.jotaro.jotarocode.agent.tools.SubagentTool;
import org.jotaro.jotarocode.agent.tools.ToolContributor;
import org.jotaro.jotarocode.config.ChatClientRegistry;
import org.jotaro.jotarocode.config.JotaroAgentProperties;
import org.jotaro.jotarocode.exception.BizException;
import org.jotaro.jotarocode.mcp.McpToolCatalog;
import org.jotaro.jotarocode.mcp.McpToolNames;
import org.jotaro.jotarocode.skill.SkillCatalog;
import org.jotaro.jotarocode.tui.ToolGlance;
import org.jotaro.jotarocode.tui.ToolQuestionnaire;
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
 * {@link JotaroAgent} 的组装工厂，负责将配置、模型客户端、本地工具、MCP 远程工具、技能目录及知识库信息装配成一个可直接运行的会话实例。
 *
 * <p>核心职责与调用链角色：
 * <ul>
 *   <li><b>工具统一适配与去重</b>：在容器启动后（{@link #registerTools()}），扫描所有开启的 {@link ToolContributor}，
 *       利用 Spring AI 的 {@link ToolCallbacks#from(Object...)} 解析出 {@code @Tool} 方法并包装成 {@link CallbackAgentTool}；
 *       随后与 {@link McpToolCatalog} 发现的远程 MCP 工具合并（本地工具优先，重名的 MCP 工具会被跳过），并将全量工具绑定给 {@link SubagentTool}。</li>
 *   <li><b>系统提示词动态拼接</b>：在 {@link #create(String, String)} 创建会话时，依次将基础 System Prompt、
 *       {@link SkillCatalog} 扫描到的技能摘要、MCP 可用性说明、以及 {@link RagBases} 知识库列表拼入最终的系统提示词。</li>
 *   <li><b>模型解析与热切换</b>：根据配置与 {@link ChatClientRegistry} 解析默认模型，并提供运行外切换会话模型的能力。</li>
 * </ul>
 */
@Slf4j
@Component
public class JotaroAgentFactory {

    /** 多模型 ChatClient 注册表，按模型名维护对应的 ChatClient 实例。 */
    @Resource
    private ChatClientRegistry chatClientRegistry;

    /** 容器中所有的内置工具提供者（如文件读写、Bash、WebSearch、RAG、Subagent 等）。 */
    @Resource
    private List<ToolContributor> contributors;

    /** Agent 全局配置项（默认模型、系统提示词、最大步数、消息窗口、RAG 库配置等）。 */
    @Resource
    private JotaroAgentProperties properties;

    /** 技能目录加载器，负责生成拼入系统提示词的可用技能列表段落。 */
    @Resource
    private SkillCatalog skillCatalog;

    /** MCP 客户端工具目录，负责发现并拉取已连接 MCP Server 的工具回调。 */
    @Resource
    private McpToolCatalog mcpToolCatalog;

    /** 仅包含本地内置 {@link ToolContributor} 适配出的工具列表。 */
    private List<AgentTool> localTools = List.of();

    /** 合并了本地内置工具与远程 MCP 工具后的完整工具列表。 */
    private List<AgentTool> tools = List.of();

    /**
     * Bean 初始化完成后注册并合并全部工具。
     *
     * <p>为什么需要对 {@link SubagentTool} 单独调用 {@code bind(tools)}：
     * {@code SubagentTool} 本身也是一个 {@link ToolContributor}，但它在启动子代理时需要从父代理的完整工具集中筛选工具（排除自身）。
     * 为了避免 Bean 循环依赖，这里在全部工具组装完毕后再通过 {@code bind} 回填给 {@code SubagentTool}。
     */
    @PostConstruct
    void registerTools() {
        this.localTools = adaptTools(contributors);
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

    /**
     * 获取启动时从 MCP 服务发现、且没有和本地内置工具重名的远程工具名称集合。
     *
     * @return 生效的 MCP 工具名集合（保持注册顺序）
     */
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

    /**
     * 创建一个新的 {@link JotaroAgent} 会话实例。
     *
     * <p>技能目录和知识库提示在创建会话时一次性拼入 {@code resolvedPrompt}，后续在该会话上调用 {@link #switchModel} 不会重新扫描或覆盖系统提示词。
     *
     * @param model        期望使用的模型名（若为空或未注册，则自动回退到 {@link #defaultModel()}）
     * @param systemPrompt 自定义系统提示词（若为空，则使用配置文件中的默认系统提示词）
     * @return 组装完成的 {@link JotaroAgent}
     */
    public JotaroAgent create(String model, String systemPrompt) {
        String resolvedModel = StringUtils.hasText(model) && chatClientRegistry.get(model) != null
                ? model
                : defaultModel();
        String resolvedPrompt = StringUtils.hasText(systemPrompt) ? systemPrompt : properties.getSystemPrompt();
        // 拼接可用 Skill 摘要段落，引导模型在合适场景下用 read 工具读取对应的 SKILL.md
        String skills = skillCatalog.promptSection();
        if (StringUtils.hasText(skills)) {
            resolvedPrompt = resolvedPrompt + "\n\n" + skills;
        }
        // 存在有效 MCP 工具时，提醒模型可按前缀调用
        if (!mcpToolNames().isEmpty()) {
            resolvedPrompt = resolvedPrompt + "\n\n已连接的 MCP 工具也可以调用。工具名以服务名开头，参数按其说明填写。";
        }
        // 拼接已配置的 RAG 知识库清单
        String bases = RagBases.prompt(properties.getRag().getBases());
        if (StringUtils.hasText(bases)) {
            resolvedPrompt = resolvedPrompt + "\n\n" + bases;
        }
        SpringAiAssistantStreamer streamer = streamerFor(resolvedModel);
        ContextCompactor compactor = new ContextCompactor(
                properties.getCompactRatio(),
                properties.getCompactThreshold(),
                properties.getDefaultContextTokens(),
                properties.getModelContextLimits()
        );
        JotaroAgent agent = new JotaroAgent(
                resolvedPrompt,
                tools,
                streamer,
                properties.getMaxSteps(),
                properties.getMessageWindow(),
                ToolExecutionMode.PARALLEL,
                null,
                compactor
        );
        agent.useModel(resolvedModel, streamer);
        return agent;
    }

    /**
     * 将实现了 {@link ToolContributor} 的 Spring Bean 适配为运行时统一认识的 {@link AgentTool}。
     *
     * <p>关掉 Spring AI 的自动工具执行（{@code internalToolExecutionEnabled(false)}）后，
     * 请求模型时仍需传入 {@link ToolCallback} 以便把 JSON Schema 参数定义发给模型，但真正的执行交由 {@code AgentLoop} 调度。
     * 这里按每个 {@link ToolContributor#enabled()} 过滤，并把工具的内置标记、TUI 简短标签、是否需要用户确认及串并行模式一并注册到位。
     *
     * @param contributors 容器中的工具提供者列表
     * @return 适配后的本地工具列表
     */
    private static List<AgentTool> adaptTools(List<ToolContributor> contributors) {
        if (contributors == null || contributors.isEmpty()) {
            return List.of();
        }
        List<AgentTool> agentTools = new ArrayList<>();
        for (ToolContributor contributor : contributors) {
            if (contributor == null || !contributor.enabled()) {
                continue;
            }
            ToolCallback[] callbacks = ToolCallbacks.from(contributor);
            for (ToolCallback callback : callbacks) {
                CallbackAgentTool tool = new CallbackAgentTool(callback, contributor.executionMode());
                McpToolNames.registerBuiltin(tool.name());
                ToolGlance.registerLabel(tool.name(), contributor.displayLabel());
                if (contributor.requiresConfirmation()) {
                    ToolQuestionnaire.registerAsk(tool.name());
                }
                agentTools.add(tool);
            }
        }
        return agentTools;
    }

    /**
     * 合并本地内置工具与远程 MCP 工具回调。
     *
     * <p>冲突处理策略：本地内置工具优先级更高。如果远程 MCP 工具与已有工具重名，打印告警并跳过该 MCP 工具，防止外部 MCP 服务覆盖核心内置工具。
     *
     * @param local  本地工具列表
     * @param remote MCP 工具回调列表
     * @return 去重合并后的不可变工具列表
     */
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

    /**
     * 获取当前已注册的所有模型名称（按字典序升序排列）。
     *
     * @return 排序后的可用模型名列表
     */
    public List<String> modelNames() {
        return chatClientRegistry.keys().stream().sorted().toList();
    }

    /**
     * 解析默认模型名称：优先取配置文件中的 {@code jotaro.agent.model}（需在注册表中存在），否则取字典序第一个可用模型。
     *
     * @return 默认模型名
     */
    public String defaultModel() {
        String configured = properties.getModel();
        if (StringUtils.hasText(configured) && chatClientRegistry.get(configured) != null) {
            return configured;
        }
        return modelNames().stream().findFirst().orElse(configured);
    }

    /**
     * 为已有会话切换模型。若会话正在运行，会将底层抛出的 {@link IllegalStateException} 转换为业务异常 {@link BizException}。
     *
     * @param agent 目标会话实例
     * @param model 目标模型名称（为空时回退到默认模型）
     */
    public void switchModel(JotaroAgent agent, String model) {
        String resolvedModel = StringUtils.hasText(model) ? model : defaultModel();
        SpringAiAssistantStreamer streamer = streamerFor(resolvedModel);
        try {
            agent.useModel(resolvedModel, streamer);
        } catch (IllegalStateException exception) {
            throw new BizException(exception.getMessage());
        }
    }

    /**
     * 根据模型名查找对应的 {@link ChatClient} 并包装为 {@link SpringAiAssistantStreamer}。
     *
     * @param model 模型名称
     * @return 对应模型的流式调用器
     * @throws BizException 当模型未在 {@link ChatClientRegistry} 中注册时抛出
     */
    private SpringAiAssistantStreamer streamerFor(String model) {
        ChatClient chatClient = chatClientRegistry.get(model);
        if (chatClient == null) {
            throw new BizException("未找到模型: " + model);
        }
        return new SpringAiAssistantStreamer(chatClient);
    }
}
