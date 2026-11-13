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
 * 父代理把一件独立任务交给子代理。一次调用可以是单个、并行或链式。
 * 子代理使用自己的消息列表，工具集是父代理的子集，并且不包含本工具。
 */
@Component
public class SubagentTool implements ToolContributor {

    @Resource
    private ChatClientRegistry registry;
    @Resource
    private JotaroAgentProperties properties;
    @Resource
    private SubagentCatalog catalog;

    private List<AgentTool> childTools = List.of();

    /**
     * 工厂装配完全部工具后调用。子代理拿到除自己以外的工具。
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
        SubagentScope.Context scope = SubagentScope.current();
        String model = scope != null && scope.model() != null && !scope.model().isBlank()
                ? scope.model()
                : properties.getModel();
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

    private SpringAiAssistantStreamer streamer(String model) {
        ChatClient client = registry.get(model);
        if (client == null) {
            throw new IllegalArgumentException("未找到模型: " + model);
        }
        return new SpringAiAssistantStreamer(client);
    }

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

    public record Task(
            @JsonPropertyDescription("Subagent name, such as explore or general.")
            String agent,
            @JsonPropertyDescription("Self-contained task. In a chain, {previous} is replaced with the previous result.")
            String task
    ) {
    }
}
