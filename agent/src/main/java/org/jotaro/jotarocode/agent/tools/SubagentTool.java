package org.jotaro.jotarocode.agent.tools;

import org.jotaro.jotarocode.agent.runtime.AgentTool;
import org.jotaro.jotarocode.agent.runtime.SpringAiAssistantStreamer;
import org.jotaro.jotarocode.agent.runtime.SubagentScope;
import org.jotaro.jotarocode.agent.subagent.SubagentCatalog;
import org.jotaro.jotarocode.agent.subagent.SubagentRunner;
import org.jotaro.jotarocode.config.ChatClientRegistry;
import org.jotaro.jotarocode.config.JotaroAgentProperties;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 子代理委派工具（{@code subagent}）。
 *
 * <p>允许父代理将自包含的子任务委派给拥有独立上下文窗口的子代理执行。支持三种调用模式：
 * <ul>
 *   <li><b>单任务模式（Single）</b>：传入 {@code agent} 和 {@code task}，执行单个子代理任务。</li>
 *   <li><b>并行模式（Parallel）</b>：传入 {@code tasks} 列表（最多 8 个），并发执行多个互不依赖的子任务。</li>
 *   <li><b>链式模式（Chain）</b>：传入 {@code chain} 列表，按顺序串行执行，后一步可通过 {@code {previous}} 引用前一步的输出。</li>
 * </ul>
 *
 * <p>子代理拥有独立的消息历史，其可用工具集为父代理工具集的子集，且固定剔除 {@code subagent} 工具本身，防止无限递归套娃。
 */
@Component
public class SubagentTool implements ToolContributor {

    /** 聊天模型客户端注册表，用于按模型名称获取对应的 {@link ChatClient}。 */
    @Resource
    private ChatClientRegistry registry;
    /** Agent 全局配置属性（默认模型名称、消息滑动窗口大小等）。 */
    @Resource
    private JotaroAgentProperties properties;
    /** 子代理定义目录，提供内置（如 {@code explore}、{@code general}）及自定义子代理配置。 */
    @Resource
    private SubagentCatalog catalog;

    /** 可供子代理使用的工具列表（已排除 {@code subagent} 自身）。 */
    private List<AgentTool> childTools = List.of();

    @Override
    public String displayLabel() {
        return "Agent";
    }

    /**
     * 在工具工厂完成全部工具装配后回调绑定。
     * 过滤掉名为 {@code "subagent"} 的工具，确保子代理无法再次派发子代理。
     *
     * @param tools 父代理持有的完整工具列表
     */
    public void bind(List<AgentTool> tools) {
        List<AgentTool> children = new ArrayList<>();
        if (tools != null) {
            for (AgentTool tool : tools) {
                if (!"subagent".equals(tool.name())) {
                    children.add(tool);
                }
            }
        }
        this.childTools = List.copyOf(children);
    }

    /**
     * 委派并执行子代理任务（支持单任务、并行任务列表或顺序任务链）。
     *
     * @param agent 单任务模式下的子代理名称（当 {@code tasks} 或 {@code chain} 非空时忽略）
     * @param task  单任务模式下的任务描述（当 {@code tasks} 或 {@code chain} 非空时忽略）
     * @param tasks 并行执行的独立子任务列表（最多 8 个）
     * @param chain 串行执行的任务步骤列表，后续步骤任务描述中可使用 {@code {previous}} 占位符注入上一步结果
     * @return 子代理执行汇总结果文本
     */
    @Tool(
            name = "subagent",
            description = "Delegate a self-contained task to a subagent with an isolated context window. "
                    + "Single: pass agent and task. Parallel: pass tasks (max 8). "
                    + "Chain: pass chain; a later task may contain {previous} for the previous step's result. "
                    + "Built-in agents: explore (read-only) and general (can edit and run commands). "
                    + "A subagent cannot call subagent."
    )
    public String subagent(
            @ToolParam(description = "Subagent name for a single task. Ignored when tasks or chain is set.", required = false)
            String agent,
            @ToolParam(description = "Task for the single subagent. Ignored when tasks or chain is set.", required = false)
            String task,
            @ToolParam(description = "Independent tasks to run in parallel. Each item has agent and task.", required = false)
            List<Task> tasks,
            @ToolParam(description = "Sequential steps. A step's task may include {previous}.", required = false)
            List<Task> chain
    ) {
        // 优先继承当前会话作用域（SubagentScope）中选定的模型，否则回退到全局默认模型
        SubagentScope.Context scope = SubagentScope.current();
        String model = scope != null && scope.model() != null && !scope.model().isBlank()
                ? scope.model()
                : properties.getModel();
        if (registry.get(model) == null) {
            model = registry.keys().stream().sorted().findFirst().orElse(model);
        }
        SubagentRunner.Environment environment = new SubagentRunner.Environment(
                childTools,
                model,
                properties.getMessageWindow(),
                this::streamer
        );
        return new SubagentRunner().execute(
                agent,
                task,
                toTasks(tasks),
                toTasks(chain),
                catalog.agents(),
                environment
        );
    }

    /**
     * 根据模型名称创建对应的流式对话执行器 {@link SpringAiAssistantStreamer}。
     */
    private SpringAiAssistantStreamer streamer(String model) {
        ChatClient client = registry.get(model);
        if (client == null) {
            throw new IllegalArgumentException("未找到模型: " + model);
        }
        return new SpringAiAssistantStreamer(client);
    }

    /**
     * 将工具入参中的 {@link Task} 列表转换为 {@link SubagentRunner.Task} 列表。
     */
    private static List<SubagentRunner.Task> toTasks(List<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        List<SubagentRunner.Task> converted = new ArrayList<>();
        for (Task task : tasks) {
            if (task == null) {
                converted.add(new SubagentRunner.Task(null, null));
            } else {
                converted.add(new SubagentRunner.Task(task.agent(), task.task()));
            }
        }
        return converted;
    }

    /**
     * 子代理任务项定义（用于并行任务列表或串行任务链）。
     *
     * @param agent 目标子代理名称（如 {@code explore} 或 {@code general}）
     * @param task  独立任务指令描述；在链式调用中可包含 {@code {previous}} 占位符
     */
    public record Task(
            @JsonPropertyDescription("Subagent name, such as explore or general.")
            String agent,
            @JsonPropertyDescription("Self-contained task. In a chain, {previous} is replaced with the previous result.")
            String task
    ) {
    }
}
