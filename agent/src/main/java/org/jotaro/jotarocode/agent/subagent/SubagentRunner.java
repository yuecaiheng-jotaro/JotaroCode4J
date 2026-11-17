package org.jotaro.jotarocode.agent.subagent;

import org.jotaro.jotarocode.agent.AgentState;
import org.jotaro.jotarocode.agent.JotaroAgent;
import org.jotaro.jotarocode.agent.runtime.AgentEventType;
import org.jotaro.jotarocode.agent.runtime.AgentTool;
import org.jotaro.jotarocode.agent.runtime.AssistantStreamer;
import org.jotaro.jotarocode.agent.runtime.RunControl;
import org.jotaro.jotarocode.agent.runtime.SubagentActivity;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 子代理（Subagent）执行器，负责隔离上下文并调度单个或多个子代理任务。
 *
 * <p>核心设计原则：
 * <ul>
 *   <li><b>上下文隔离</b>：每个子代理都是一个全新创建的 {@link JotaroAgent}，拥有独立的消息历史，看不到父会话的冗长对话，
 *       仅接收分配给它的具体 {@code task}，并在完成后只将最终文本结果（截断至 {@link #MAX_CHARS}）返回给父代理，从而大幅节省父会话的上下文窗口。</li>
 *   <li><b>禁止递归派生</b>：在调用 {@link #select(List, List)} 筛选工具时，强制排除名为 {@code subagent} 的工具，防止子代理无限套娃派生孙代理。</li>
 *   <li><b>跨线程上下文继承</b>：并行执行时通过显式传递 {@link SubagentScope} 和 {@link RunControl}，
 *       确保子代理工作线程能继承父会话的工具确认钩子（{@code beforeToolCall}）、实时活动回调（{@code activity}）以及取消开关（父会话 abort 时子代理立即停止）。</li>
 *   <li><b>三种调度模式</b>：支持单任务直跑、{@code tasks} 多任务并行（线程池限流 {@link #MAX_CONCURRENCY}，部分失败不中断整体）、
 *       以及 {@code chain} 顺序流水线（支持通过 {@code {previous}} 占位符注入上一步的输出）。</li>
 * </ul>
 */
public final class SubagentRunner {

    /** 单次批量并行或顺序链允许的最大任务数。 */
    public static final int MAX_TASKS = 8;

    /** 并行模式下的最大并发工作线程数。 */
    public static final int MAX_CONCURRENCY = 4;

    /** 单个子代理运行允许的最大循环步数（Turn 数量）。 */
    public static final int MAX_STEPS = 8;

    /** 单个子代理返回给父代理的最大结果字符数，超出部分截断以保护父代理上下文窗口。 */
    private static final int MAX_CHARS = 50_000;

    /** 子代理工具自身的名称，为子代理挑选工具时始终排除该名称以禁止递归调用。 */
    private static final String TOOL_NAME = "subagent";

    /** 子代理调用实例递增序号，用于生成如 {@code explore-1} 的唯一活动 ID，方便 TUI 区分并行的多个同类子代理。 */
    private final AtomicInteger sequence = new AtomicInteger();

    /**
     * 并行或链式调用中的单个子任务项。
     *
     * @param agent 目标子代理角色名
     * @param task  交给该子代理的任务描述（在 {@code chain} 模式下支持 {@code {previous}} 占位符）
     */
    public record Task(String agent, String task) {
    }

    /**
     * 子代理运行所需的宿主环境依赖。
     *
     * @param tools         父代理的全量工具列表，子代理从中按白名单挑选，并始终排除 {@code subagent}
     * @param model         父会话当前正在使用的模型名（当子代理定义未指定模型时使用）
     * @param messageWindow 子代理发给模型的滑动消息窗口大小
     * @param streamers     按模型名获取对应 {@link AssistantStreamer} 的工厂函数
     */
    public record Environment(
            List<AgentTool> tools,
            String model,
            int messageWindow,
            Function<String, AssistantStreamer> streamers
    ) {
    }

    /**
     * 统一执行入口：根据参数优先级分发到链式执行（{@code chain}）、并行执行（{@code tasks}）或单任务执行（{@code agent + task}）。
     *
     * @param agent       单任务模式下的子代理角色名
     * @param task        单任务模式下的任务描述
     * @param tasks       并行任务列表
     * @param chain       顺序链式任务列表
     * @param agents      当前可用的全部子代理角色定义
     * @param environment 宿主运行环境
     * @return 汇总或单次执行的最终文本报告
     */
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

    /**
     * 顺序执行任务链：每一步的 {@code task} 中若包含 {@code {previous}}，会自动替换为上一步子代理的输出结果。
     *
     * @param chain       任务链列表
     * @param agents      可用子代理定义
     * @param environment 宿主运行环境
     * @return 按步骤编号拼接的 Markdown 汇总报告
     */
    private String runChain(List<Task> chain, List<SubagentDefinition> agents, Environment environment) {
        if (chain.size() > MAX_TASKS) {
            throw new IllegalArgumentException("chain 最多 " + MAX_TASKS + " 步，当前 " + chain.size());
        }
        String previous = "";
        List<String> sections = new ArrayList<>();
        for (int index = 0; index < chain.size(); index++) {
            Task step = chain.get(index);
            // 将模板中的 {previous} 替换为前序步骤的实际产出
            String prompt = step.task() == null ? "" : step.task().replace("{previous}", previous);
            if (prompt.isBlank()) {
                throw new IllegalArgumentException("chain[" + index + "].task 不能为空");
            }
            previous = runOne(require(agents, step.agent()), prompt, environment);
            sections.add(section(index, step.agent(), prompt, previous));
        }
        return String.join("\n\n", sections);
    }

    /**
     * 并行执行多个子代理任务。
     *
     * <p>并发与容错细节：
     * <ul>
     *   <li>在提交线程池前先校验全部任务参数与角色存在性，做到快速失败（Fail-fast）。</li>
     *   <li>由于 {@link SubagentScope} 和 {@link RunControl} 都存放在 {@link ThreadLocal} 中，
     *       必须在每个子线程内部通过 {@code SubagentScope.call} 和 {@code RunControl.install} 重新挂载，
     *       否则子线程里的工具确认钩子、UI 活动上报以及 Ctrl+C 中断都会失效。</li>
     *   <li>单个子任务失败不会打断其他并行任务，只有当全部任务均失败时才向上抛出 {@link IllegalStateException}。</li>
     * </ul>
     *
     * @param tasks       并行任务列表
     * @param agents      可用子代理定义
     * @param environment 宿主运行环境
     * @return 按原始任务顺序拼接的 Markdown 汇总报告
     */
    private String runParallel(List<Task> tasks, List<SubagentDefinition> agents, Environment environment) {
        if (tasks.size() > MAX_TASKS) {
            throw new IllegalArgumentException("tasks 最多 " + MAX_TASKS + " 个，当前 " + tasks.size());
        }
        // 提交前统一校验，避免部分线程已经跑起来才发现后面某个任务的 agent 名字拼错
        for (int index = 0; index < tasks.size(); index++) {
            Task step = tasks.get(index);
            if (step.task() == null || step.task().isBlank()) {
                throw new IllegalArgumentException("tasks[" + index + "].task 不能为空");
            }
            require(agents, step.agent());
        }
        // 捕获调用线程上的父级 SubagentScope 与 RunControl，供工作线程跨线程还原
        SubagentScope.Context scope = SubagentScope.current();
        RunControl parent = RunControl.current();
        int threads = Math.min(MAX_CONCURRENCY, tasks.size());
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "jotaro-subagent");
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
            // 仅当整批任务全部失败时才作为异常抛出，触发工具调用层的 error 标记
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

    /**
     * 实例化并运行单个子代理，订阅其内部事件以收集最终回复文本并向父级上报实时活动。
     *
     * @param definition  子代理角色定义
     * @param task        具体任务指令
     * @param environment 宿主运行环境
     * @return 截断处理后的子代理最终答复文本
     */
    private String runOne(SubagentDefinition definition, String task, Environment environment) {
        String model = definition.model() == null || definition.model().isBlank()
                ? environment.model()
                : definition.model();
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("子代理 " + definition.name() + " 没有可用模型");
        }
        AssistantStreamer streamer = environment.streamers().apply(model);
        RunControl parent = RunControl.current();
        // 为子任务创建拥有独立消息历史的 JotaroAgent，但沿用父运行的取消开关 parent 和工具审批钩子
        JotaroAgent child = new JotaroAgent(
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
        Consumer<SubagentActivity> activity = scope == null ? null : scope.activity();
        String id = definition.name() + "-" + sequence.incrementAndGet();
        // text 始终只保留最后一轮 AssistantMessage 的正文作为子代理最终结论
        StringBuilder text = new StringBuilder();
        AtomicBoolean limited = new AtomicBoolean();
        // 标记当前 Turn 是否已经通过流式增量上报过文本，避免在 MESSAGE_END 时重复上报一次全文
        AtomicBoolean streamed = new AtomicBoolean();
        tell(activity, id, definition.name(), task, SubagentActivity.Kind.STARTED, task);
        child.subscribe(event -> {
            if (event.getType() == AgentEventType.TURN_START) {
                streamed.set(false);
            }
            if (event.getType() == AgentEventType.MESSAGE_UPDATE && event.isReasoning()) {
                tell(activity, id, definition.name(), task, SubagentActivity.Kind.THINKING, "");
            }
            if (event.getType() == AgentEventType.MESSAGE_UPDATE && !event.isReasoning()
                    && event.getTextDelta() != null && !event.getTextDelta().isEmpty()) {
                streamed.set(true);
                tell(activity, id, definition.name(), task, SubagentActivity.Kind.TEXT, event.getTextDelta());
            }
            if (event.getType() == AgentEventType.MESSAGE_END && event.getMessage() instanceof AssistantMessage assistant) {
                text.setLength(0);
                if (assistant.getText() != null) {
                    text.append(assistant.getText());
                }
                // 如果底层 Streamer 直接返回完整消息而没有发过 MESSAGE_UPDATE 增量，则在 MESSAGE_END 补发一次 TEXT 活动
                if (!streamed.get() && assistant.getText() != null && !assistant.getText().isBlank()) {
                    tell(activity, id, definition.name(), task, SubagentActivity.Kind.TEXT, assistant.getText());
                }
            }
            if (event.getType() == AgentEventType.TOOL_EXECUTION_START) {
                String arguments = event.getArguments() == null ? "" : event.getArguments();
                tell(activity, id, definition.name(), task, SubagentActivity.Kind.TOOL,
                        (event.getToolName() == null ? "" : event.getToolName()) + "\n" + arguments);
            }
            if (event.getType() == AgentEventType.TOOL_EXECUTION_END) {
                tell(activity, id, definition.name(), task, SubagentActivity.Kind.TOOL_RESULT,
                        event.getResult() == null ? "" : event.getResult());
            }
            if (event.getType() == AgentEventType.AGENT_END && event.isStepLimitReached()) {
                limited.set(true);
            }
        });
        try {
            child.prompt(task);
        } catch (RuntimeException exception) {
            tell(activity, id, definition.name(), task, SubagentActivity.Kind.FAILED,
                    exception.getMessage() == null ? "子代理执行失败" : exception.getMessage());
            throw exception;
        }
        if (child.getState() == AgentState.ERROR) {
            String reason = text.toString().isBlank()
                    ? "子代理 " + definition.name() + " 执行失败"
                    : text.toString();
            tell(activity, id, definition.name(), task, SubagentActivity.Kind.FAILED, reason);
            throw new IllegalStateException(reason);
        }
        String result = cap(text.toString());
        if (limited.get()) {
            result = result + "\n（已达到子代理步数上限）";
        }
        tell(activity, id, definition.name(), task, SubagentActivity.Kind.FINISHED, result);
        return result;
    }

    /**
     * 安全地向外部监听器发送子代理活动事件，吞掉展示层可能抛出的运行时异常以免中断子代理主流程。
     */
    private static void tell(Consumer<SubagentActivity> activity,
                             String id,
                             String agent,
                             String task,
                             SubagentActivity.Kind kind,
                             String detail) {
        if (activity == null) {
            return;
        }
        try {
            activity.accept(new SubagentActivity(id, agent, task, kind, detail));
        } catch (RuntimeException ignored) {
            // 展示失败不能打断子代理
        }
    }

    /**
     * 根据子代理定义的工具白名单从父工具列表中筛选可用工具，并始终剔除 {@code subagent} 工具本身。
     *
     * @param tools 父代理全量工具
     * @param allow 允许的工具名称白名单（为 {@code null} 时表示除 {@code subagent} 外全部放行）
     * @return 子代理最终可用的工具列表
     */
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

    /**
     * 按名称查找子代理定义，不存在或名称为空时抛出带可用角色提示的 {@link IllegalArgumentException}。
     */
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

    /**
     * 将所有可用子代理的名称与描述格式化为人类可读的错误提示列表。
     */
    private static String names(List<SubagentDefinition> agents) {
        List<String> names = new ArrayList<>();
        for (SubagentDefinition agent : agents) {
            names.add(agent.name() + "（" + agent.description() + "）");
        }
        return String.join("；", names);
    }

    /**
     * 组装子代理的完整系统提示词：在角色专属提示词前加上通用边界约束。
     */
    private static String prompt(SubagentDefinition definition) {
        return "你是子代理 " + definition.name() + "。只完成交给你的任务，用最终结果回复，不要再派生子代理。\n\n"
                + definition.systemPrompt();
    }

    /**
     * 将多任务（并行或链式）中的单步结果格式化为带序号、角色名与任务摘要标题的 Markdown 章节。
     */
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

    /**
     * 规范化并截断子代理输出文本：空白输出替换为占位说明，超长输出截断至 {@link #MAX_CHARS}。
     */
    private static String cap(String text) {
        if (text == null || text.isBlank()) {
            return "（子代理没有文本结果）";
        }
        if (text.length() <= MAX_CHARS) {
            return text;
        }
        return text.substring(0, MAX_CHARS) + "\n…（结果已截断）";
    }

    /**
     * 提取异常的可读信息，若异常 message 为空则回退到异常类名。
     */
    private static String message(Throwable throwable) {
        String text = throwable.getMessage();
        return text == null || text.isBlank() ? throwable.getClass().getSimpleName() : text;
    }
}
