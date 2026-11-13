package org.jotaro.jotarocode.agent.subagent;

import org.jotaro.jotarocode.agent.AgentState;
import org.jotaro.jotarocode.agent.PiAgent;
import org.jotaro.jotarocode.agent.runtime.AgentEventType;
import org.jotaro.jotarocode.agent.runtime.AgentTool;
import org.jotaro.jotarocode.agent.runtime.AssistantStreamer;
import org.jotaro.jotarocode.agent.runtime.RunControl;
import org.jotaro.jotarocode.agent.runtime.SubagentScope;
import org.jotaro.jotarocode.agent.runtime.ToolExecutionMode;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * 跑一个或多个子代理。每个子代理是新的 {@link PiAgent}，只有自己的消息，看不到父对话。
 * 工具名单里去掉 {@code subagent}，所以子代理不能再往下派。
 *
 * <p>三种用法和常见编码代理一致：单个任务、一批并行任务、按顺序传递上一步结果的链。
 */
public final class SubagentRunner {

    public static final int MAX_TASKS = 8;
    public static final int MAX_CONCURRENCY = 4;
    public static final int MAX_STEPS = 8;
    private static final int MAX_CHARS = 50_000;
    private static final String TOOL_NAME = "subagent";

    public record Task(String agent, String task) {
    }

    /**
     * @param tools         父代理的工具，子代理从这里挑，并始终排除 {@code subagent}
     * @param model         父会话当前模型
     * @param messageWindow 子代理发给模型的消息窗口
     * @param streamers     按模型名取流式客户端。定义里写了模型就用那个名字，否则用 {@code model}
     */
    public record Environment(
            List<AgentTool> tools,
            String model,
            int messageWindow,
            Function<String, AssistantStreamer> streamers
    ) {
    }

    public String execute(String agent,
                          String task,
                          List<Task> tasks,
                          List<Task> chain,
                          List<SubagentDefinition> agents,
                          Environment environment) {
        if (chain != null && !chain.isEmpty()) {
            return runChain(chain, agents, environment);
        }
        if (tasks != null && !tasks.isEmpty()) {
            return runParallel(tasks, agents, environment);
        }
        if (agent == null || agent.isBlank() || task == null || task.isBlank()) {
            throw new IllegalArgumentException("单任务需要 agent 和 task；并行用 tasks；顺序执行用 chain");
        }
        return runOne(require(agents, agent), task, environment);
    }

    private String runChain(List<Task> chain, List<SubagentDefinition> agents, Environment environment) {
        if (chain.size() > MAX_TASKS) {
            throw new IllegalArgumentException("chain 最多 " + MAX_TASKS + " 步，当前 " + chain.size());
        }
        String previous = "";
        List<String> sections = new ArrayList<>();
        for (int index = 0; index < chain.size(); index++) {
            Task step = chain.get(index);
            String prompt = step.task() == null ? "" : step.task().replace("{previous}", previous);
            if (prompt.isBlank()) {
                throw new IllegalArgumentException("chain[" + index + "].task 不能为空");
            }
            previous = runOne(require(agents, step.agent()), prompt, environment);
            sections.add(section(index, step.agent(), prompt, previous));
        }
        return String.join("\n\n", sections);
    }

    private String runParallel(List<Task> tasks, List<SubagentDefinition> agents, Environment environment) {
        if (tasks.size() > MAX_TASKS) {
            throw new IllegalArgumentException("tasks 最多 " + MAX_TASKS + " 个，当前 " + tasks.size());
        }
        for (int index = 0; index < tasks.size(); index++) {
            Task step = tasks.get(index);
            if (step.task() == null || step.task().isBlank()) {
                throw new IllegalArgumentException("tasks[" + index + "].task 不能为空");
            }
            require(agents, step.agent());
        }
        SubagentScope.Context scope = SubagentScope.current();
        RunControl parent = RunControl.current();
        int threads = Math.min(MAX_CONCURRENCY, tasks.size());
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "pi-subagent");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (Task step : tasks) {
                futures.add(pool.submit(() -> SubagentScope.call(scope, () -> {
                    RunControl previous = RunControl.install(parent);
                    try {
                        return runOne(require(agents, step.agent()), step.task(), environment);
                    } finally {
                        RunControl.install(previous);
                    }
                })));
            }
            List<String> sections = new ArrayList<>();
            int failures = 0;
            for (int index = 0; index < futures.size(); index++) {
                Task step = tasks.get(index);
                try {
                    String result = futures.get(index).get();
                    sections.add(section(index, step.agent(), step.task(), result));
                } catch (ExecutionException exception) {
                    failures++;
                    Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                    sections.add(section(index, step.agent(), step.task(), "失败: " + message(cause)));
                }
            }
            String report = String.join("\n\n", sections);
            if (failures == tasks.size()) {
                throw new IllegalStateException(report);
            }
            return report;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("子代理已中止");
        } finally {
            pool.shutdownNow();
        }
    }

    private String runOne(SubagentDefinition definition, String task, Environment environment) {
        String model = definition.model() == null || definition.model().isBlank()
                ? environment.model()
                : definition.model();
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("子代理 " + definition.name() + " 没有可用模型");
        }
        AssistantStreamer streamer = environment.streamers().apply(model);
        RunControl parent = RunControl.current();
        PiAgent child = new PiAgent(
                prompt(definition),
                select(environment.tools(), definition.tools()),
                streamer,
                MAX_STEPS,
                environment.messageWindow(),
                ToolExecutionMode.PARALLEL,
                parent
        );
        SubagentScope.Context scope = SubagentScope.current();
        if (scope != null && scope.beforeToolCall() != null) {
            child.setBeforeToolCall(scope.beforeToolCall());
        }
        StringBuilder text = new StringBuilder();
        AtomicBoolean limited = new AtomicBoolean();
        child.subscribe(event -> {
            if (event.getType() == AgentEventType.MESSAGE_END && event.getMessage() instanceof AssistantMessage assistant) {
                text.setLength(0);
                if (assistant.getText() != null) {
                    text.append(assistant.getText());
                }
            }
            if (event.getType() == AgentEventType.AGENT_END && event.isStepLimitReached()) {
                limited.set(true);
            }
        });
        child.prompt(task);
        if (child.getState() == AgentState.ERROR) {
            String reason = text.toString().isBlank()
                    ? "子代理 " + definition.name() + " 执行失败"
                    : text.toString();
            throw new IllegalStateException(reason);
        }
        String result = cap(text.toString());
        if (limited.get()) {
            result = result + "\n（已达到子代理步数上限）";
        }
        return result;
    }

    private static List<AgentTool> select(List<AgentTool> tools, List<String> allow) {
        Set<String> names = allow == null ? null : new HashSet<>(allow);
        List<AgentTool> selected = new ArrayList<>();
        if (tools == null) {
            return selected;
        }
        for (AgentTool tool : tools) {
            if (TOOL_NAME.equals(tool.name())) {
                continue;
            }
            if (names == null || names.contains(tool.name())) {
                selected.add(tool);
            }
        }
        return List.copyOf(selected);
    }

    private static SubagentDefinition require(List<SubagentDefinition> agents, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("子代理名称不能为空。可用: " + names(agents));
        }
        String wanted = name.trim();
        for (SubagentDefinition agent : agents) {
            if (wanted.equals(agent.name())) {
                return agent;
            }
        }
        throw new IllegalArgumentException("未知子代理: " + wanted + "。可用: " + names(agents));
    }

    private static String names(List<SubagentDefinition> agents) {
        List<String> names = new ArrayList<>();
        for (SubagentDefinition agent : agents) {
            names.add(agent.name() + "（" + agent.description() + "）");
        }
        return String.join("；", names);
    }

    private static String prompt(SubagentDefinition definition) {
        return "你是子代理 " + definition.name() + "。只完成交给你的任务，用最终结果回复，不要再派生子代理。\n\n"
                + definition.systemPrompt();
    }

    private static String section(int index, String agent, String task, String body) {
        String title = task == null ? "" : task.strip();
        int newline = title.indexOf('\n');
        if (newline >= 0) {
            title = title.substring(0, newline);
        }
        if (title.length() > 80) {
            title = title.substring(0, 80) + "...";
        }
        String heading = "## " + (index + 1) + ". " + (agent == null ? "" : agent.trim());
        if (!title.isEmpty()) {
            heading = heading + " — " + title;
        }
        return heading + "\n" + body;
    }

    private static String cap(String text) {
        if (text == null || text.isBlank()) {
            return "（子代理没有文本结果）";
        }
        if (text.length() <= MAX_CHARS) {
            return text;
        }
        return text.substring(0, MAX_CHARS) + "\n…（结果已截断）";
    }

    private static String message(Throwable throwable) {
        String text = throwable.getMessage();
        return text == null || text.isBlank() ? throwable.getClass().getSimpleName() : text;
    }
}
