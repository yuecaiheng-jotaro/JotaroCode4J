package org.jotaro.jotarocode.tui;

import org.jotaro.jotarocode.agent.JotaroAgent;
import org.jotaro.jotarocode.agent.context.ContextManager;
import org.jotaro.jotarocode.agent.context.ContextStore;
import org.jotaro.jotarocode.agent.runtime.AgentEvent;
import org.jotaro.jotarocode.agent.runtime.AgentEventType;
import org.jotaro.jotarocode.agent.runtime.ContextCompactor;
import org.jotaro.jotarocode.agent.runtime.SpringAiAssistantStreamer;
import org.jotaro.jotarocode.agent.runtime.SubagentActivity;
import org.jotaro.jotarocode.config.ChatClientRegistry;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 终端交互界面（TUI）主控类。
 * <p>
 * 核心交互与状态流转设计：
 * <ul>
 *   <li>底部维护一个带上下边框的输入区：空闲时顶栏显示 {@code jotaro}，运行中切换为 {@code steer}，运行结束后立即切回 {@code jotaro}。</li>
 *   <li>正在生成的半行流式文字、当前执行的工具或子代理（{@link SubagentPane}）的实时动作显示在输入框上方的状态带中，配以 80ms 刷新的旋转动画。</li>
 *   <li>Agent 推理循环在独立的后台线程 {@code jotaro-tui} 中执行，主线程保持 JLine {@code readLine} 循环，以便在运行期间随时接收工具确认问卷回答、追加 steering 指令或响应 {@code Ctrl+C} 中断。</li>
 *   <li>普通文本下按上下方向键回溯历史输入；行首为 {@code /} 时，上下键在单列候选菜单中移动，回车选定，{@code Esc} 或 {@code Ctrl+C} 取消菜单并恢复原输入。</li>
 *   <li>长文本（超过 5 行或 800 字符）与图片粘贴会在输入框内折叠为单行摘要标签（如 {@code [Pasted text #1 +N lines]}、{@code [Image #1]}），提交时再展开为全文与附件发给模型。</li>
 *   <li>执行写文件、编辑或命令类复杂工具前，会通过 {@link QuestionGate} 拦截并弹出确认问卷：{@code y} 允许本次，{@code a} 本次会话全部允许，{@code n} 拒绝，其他文字作为提示词反馈给模型。</li>
 * </ul>
 */
public final class JotaroAgentTui {

    /** 运行中状态条左侧循环播放的盲文点阵旋转帧。 */
    private static final String[] SPIN = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    /** 当前会话绑定的核心 Agent 实例。 */
    private final JotaroAgent agent;
    /** 模型客户端注册表，用于查询可用模型及切换模型。 */
    private final ChatClientRegistry registry;
    /** 当前选用的模型名称。 */
    private volatile String model;
    /** 将 Agent 事件格式化为终端输出行与实时半行的格式化器。 */
    private final AgentEventFormatter formatter = new AgentEventFormatter();
    /** 子代理嵌套块与状态格式化器。 */
    private final SubagentPane subagents = new SubagentPane();
    /** 后台单线程执行器：运行 Agent 推理循环，使主读行线程保持响应（处理问卷、steering 与中断）。 */
    private final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "jotaro-tui");
        thread.setDaemon(true);
        return thread;
    });
    /** 定时调度器：每 80ms 推进一次旋转动画并重绘输入框上方状态行。 */
    private final ScheduledExecutorService spinner = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "jotaro-spin");
        thread.setDaemon(true);
        return thread;
    });
    /** 标记用户是否已通过 {@code /exit}、{@code /quit} 或 EOF 退出主循环。 */
    private volatile boolean stopped;
    /** 标记后台任务已提交、正处于运行展示态（覆盖从提交任务到 {@code agent.isRunning()} 变为 true 之间的微小间隙）。 */
    private volatile boolean showingRun;
    /** 当前旋转动画帧下标。 */
    private volatile int spin;
    /** 输入框上方显示的当前主代理活动描述（如 {@code 正在回复} 或当前工具摘要）。 */
    private volatile String activity = "正在回复";
    /** 当前正在执行 {@code agent.prompt(...)} 的后台工作线程引用，用于 {@code Ctrl+C} 时精确发送线程中断。 */
    private volatile Thread runThread;
    /** 终端画面重绘与事件格式化同步锁。 */
    private final Object screen = new Object();
    /** 复杂工具执行前的确认问卷门控。 */
    private QuestionGate questions;
    /** 定制的 JLine 单列补全与粘贴折叠行读取器。 */
    private SingleColumnLineReader reader;

    /**
     * 构造 TUI 终端交互控制器。
     *
     * @param agent    Agent 实例
     * @param registry 模型客户端注册表
     * @param model    初始模型名称
     */
    public JotaroAgentTui(JotaroAgent agent, ChatClientRegistry registry, String model) {
        this.agent = agent;
        this.registry = registry;
        this.model = model;
    }

    /**
     * 启动终端交互主循环，阻塞直到用户输入退出命令或按下 {@code Ctrl+D}。
     *
     * @throws Exception 终端初始化或读取异常
     */
    public void run() throws Exception {
        Terminal terminal = TerminalBuilder.builder().system(true).dumb(true).build();
        reader = new SingleColumnLineReader(terminal, agent::isRunning, () -> this.model);
        reader.setCompleter(new SlashCommandCompleter(
                () -> registry.keys().stream().sorted().toList(),
                () -> agent.listResumableSessions(10)
        ));
        reader.setHistory(new DefaultHistory());
        reader.installInteractionKeys();
        reader.setAutosuggestion(LineReader.SuggestionType.COMPLETER);
        questions = new QuestionGate(lines -> printLines(reader, lines));
        // 挂载工具执行前确认钩子、子代理活动回调与主代理事件订阅
        agent.setBeforeToolCall(questions::beforeCall);
        agent.setSubagentActivity(this::onSubagent);
        agent.subscribe(this::onEvent);
        spinner.scheduleAtFixedRate(this::tick, 80, 80, TimeUnit.MILLISECONDS);
        printLines(reader, List.of(PromptStyle.banner()));
        while (!stopped) {
            String line;
            try {
                reader.setFooter(PromptStyle.footer(model, showingRun || agent.isRunning(), reader.columns()));
                line = reader.readLine(currentFrame(), null, (Character) null, null);
            } catch (UserInterruptException exception) {
                // 运行中按下 Ctrl+C 触发 UserInterruptException，中断当前运行任务
                interruptRun(reader);
                continue;
            } catch (EndOfFileException exception) {
                break;
            }
            if (line == null) {
                break;
            }
            handle(reader, line.trim());
        }
        // 退出前清理正在运行的任务、问卷等待与后台线程池
        agent.abort();
        if (questions != null) {
            questions.cancel();
        }
        executor.shutdownNow();
        spinner.shutdownNow();
    }

    /**
     * 定时器回调：当 Agent 正在运行且当前没有实时半行文字时，推进旋转帧并刷新状态栏。
     */
    private void tick() {
        if (stopped || reader == null || (!showingRun && !agent.isRunning())) {
            return;
        }
        if (!formatter.livePlain().isEmpty()) {
            return;
        }
        spin = (spin + 1) % SPIN.length;
        synchronized (screen) {
            paint(List.of());
        }
    }

    /**
     * 分发处理用户提交的一行输入：斜杠命令、工具问卷回答、运行中 steering 插入或新启一轮对话。
     */
    private void handle(LineReader reader, String line) {
        if (line.isEmpty()) {
            return;
        }
        // 不包含粘贴块且以 / 开头的行视为本地斜杠命令
        if (line.startsWith("/") && !this.reader.containsPaste(line)) {
            handleCommand(reader, line);
            return;
        }
        // 将输入框中的粘贴摘要标签展开为完整文本与图片路径
        PasteChunks.Expanded expanded = this.reader.expandPaste(line);
        // 若当前正停在工具执行前的确认问卷，则优先将本行作为问卷回答消费
        if (questions != null && questions.isWaiting()) {
            questions.answer(expanded.text());
            return;
        }
        // JLine 已把提交的输入留在终端上；若 Agent 正在运行，则作为 steering 插入当前轮
        if (agent.isRunning()) {
            printLines(reader, List.of(PromptStyle.dim("  插入当前回复")));
            agent.prompt(expanded.text(), this.reader.mediaOf(expanded));
            return;
        }
        // 空闲状态下提交后台推理任务
        showingRun = true;
        activity = "正在回复";
        executor.submit(() -> {
            runThread = Thread.currentThread();
            try {
                agent.prompt(expanded.text(), this.reader.mediaOf(expanded));
            } finally {
                // 清除后台线程可能残留的中断标志，并恢复输入框为空闲状态
                Thread.interrupted();
                if (runThread == Thread.currentThread()) {
                    runThread = null;
                }
                synchronized (screen) {
                    showingRun = false;
                    activity = "正在回复";
                    paint(List.of());
                }
            }
        });
    }

    /**
     * 主代理事件回调：更新当前活动状态描述，并将已换行的输出打印到输入框上方。
     */
    private void onEvent(AgentEvent event) {
        synchronized (screen) {
            if (event.getType() == AgentEventType.TOOL_EXECUTION_START && !"subagent".equals(event.getToolName())) {
                activity = ToolGlance.call(event.getToolName(), event.getArguments());
            } else if (event.getType() == AgentEventType.MESSAGE_UPDATE
                    || event.getType() == AgentEventType.TOOL_EXECUTION_END
                    || event.getType() == AgentEventType.TURN_START) {
                activity = "正在回复";
            }
            paint(formatter.format(event));
        }
    }

    /**
     * 子代理活动回调：由 {@link SubagentPane} 整理为嵌套行并刷新终端显示。
     */
    private void onSubagent(SubagentActivity activity) {
        synchronized (screen) {
            paint(subagents.accept(activity));
        }
    }

    /**
     * 将新完成的文本行输出到滚动区，并同步重绘输入框上方的实时半行、顶框线与底栏。
     */
    private void paint(List<String> lines) {
        if (reader == null) {
            return;
        }
        boolean running = showingRun || agent.isRunning();
        int columns = reader.columns();
        String live = PromptStyle.tail(formatter.livePlain(), columns);
        String detail = subagents.status();
        String status = detail != null ? detail : activity;
        if (running && live.isEmpty()) {
            status = SPIN[Math.floorMod(spin, SPIN.length)] + " " + status;
        }
        reader.present(
                lines,
                PromptStyle.frame(running, live, formatter.liveThinking(), status, columns),
                null,
                PromptStyle.footer(model, running, columns)
        );
    }

    /**
     * 计算新一轮 {@code readLine} 启动时的初始输入框提示符（需要将 {@code %} 转义为 {@code %%} 以防 JLine 格式化误解析）。
     */
    private String currentFrame() {
        synchronized (screen) {
            boolean running = showingRun || agent.isRunning();
            int columns = reader == null ? 80 : reader.columns();
            String live = reader == null ? "" : PromptStyle.tail(formatter.livePlain(), columns);
            String detail = subagents.status();
            String status = detail != null ? detail : activity;
            if (running && live.isEmpty()) {
                status = SPIN[Math.floorMod(spin, SPIN.length)] + " " + status;
            }
            return PromptStyle.frame(running, live, formatter.liveThinking(), status, columns).replace("%", "%%");
        }
    }

    /**
     * 中断当前正在运行的 Agent 任务、取消挂起的工具问卷并打断后台执行线程。
     */
    private void interruptRun(LineReader reader) {
        boolean running = agent.isRunning();
        agent.abort();
        if (questions != null) {
            questions.cancel();
        }
        Thread thread = runThread;
        if (running && thread != null) {
            thread.interrupt();
        }
        if (running) {
            printLines(reader, List.of("已中断当前运行"));
        }
    }

    /**
     * 解析并执行本地斜杠命令（{@code /help}、{@code /exit}、{@code /quit}、{@code /abort}、{@code /reset}、{@code /new}、{@code /model}）。
     */
    private void handleCommand(LineReader reader, String line) {
        if ("/help".equals(line)) {
            java.util.ArrayList<String> helpLines = new java.util.ArrayList<>();
            for (SlashCommandCompleter.Command command : SlashCommandCompleter.COMMANDS) {
                helpLines.add(String.format("%-15s %s", command.name(), command.description()));
            }
            helpLines.addAll(List.of(
                    "/model <name>   切换模型",
                    "Up / Down       普通文字回溯已发送的内容；/ 后面在候选里上下移动",
                    "Enter           / 后面回车选定。/model 会直接定位当前模型",
                    "Esc / Ctrl+C    候选列表开着时取消选择并收起列表",
                    "Ctrl+C          空闲时清空输入，运行时中断任务",
                    "Ctrl+Z          强制退出",
                    "问卷            bash、powershell、write、edit 执行前询问",
                    "y / a / n       允许这次 / 全部允许 / 拒绝。其他文字当作提示",
                    "Tab             按前缀补全，候选每条一行。灰色提示用 → 或 Ctrl+E 接受",
                    "普通文本        空闲时开始一轮，运行中作为 steering 插入",
                    "粘贴            超过 5 行或 800 字收成 [Pasted text #1 +N lines]，发给模型的是全文",
                    "Ctrl+V          粘贴图片，输入框显示 [Image #1]。Cmd+V 仍粘贴文字"
            ));
            printLines(reader, helpLines);
            return;
        }
        if ("/exit".equals(line) || "/quit".equals(line)) {
            stopped = true;
            agent.abort();
            return;
        }
        if ("/abort".equals(line)) {
            if (!agent.isRunning()) {
                printLines(reader, List.of("当前没有运行中的任务"));
                return;
            }
            interruptRun(reader);
            return;
        }
        if ("/reset".equals(line)) {
            try {
                agent.reset();
                if (questions != null) {
                    questions.reset();
                }
                printLines(reader, List.of("会话已清空"));
            } catch (IllegalStateException exception) {
                printLines(reader, List.of(exception.getMessage()));
            }
            return;
        }
        if ("/new".equals(line)) {
            try {
                ContextManager.ContextSnapshot snap = agent.startNew();
                if (questions != null) {
                    questions.reset();
                }
                String shortId = snap.sessionId().length() > 8
                        ? snap.sessionId().substring(0, 8)
                        : snap.sessionId();
                printLines(reader, List.of("已创建新会话 [" + shortId + "]。上一会话已保留，可用 /resume 恢复"));
            } catch (IllegalStateException exception) {
                printLines(reader, List.of(exception.getMessage()));
            }
            return;
        }
        if ("/context".equals(line)) {
            showContextInsight(reader);
            return;
        }
        if (line.equals("/goal") || line.startsWith("/goal ")) {
            updateGoal(reader, line.substring("/goal".length()).trim());
            return;
        }
        if (line.equals("/compact") || line.startsWith("/compact ")) {
            compactContext(reader, line.substring("/compact".length()).trim());
            return;
        }
        if (line.equals("/resume") || line.startsWith("/resume ")) {
            resumeSession(reader, line.substring("/resume".length()).trim());
            return;
        }
        if (line.equals("/model") || line.startsWith("/model ")) {
            switchModel(reader, line.substring("/model".length()).trim());
            return;
        }
        printLines(reader, List.of("未知命令: " + line));
    }

    /**
     * 处理 {@code /context} 命令：展示当前上下文水位、Redis/内存存储状态、旧工具裁剪数、压缩次数及目标锚点。
     */
    private void showContextInsight(LineReader reader) {
        ContextManager.ContextSnapshot snap = agent.inspectContext();
        int triggerPct = (int) Math.round(snap.triggerThreshold() * 100);
        int compactPct = (int) Math.round(snap.compactRatio() * 100);
        String usage = String.format(java.util.Locale.US, "%.1f%%", snap.usagePercent());
        List<String> lines = new java.util.ArrayList<>();
        lines.add("╭ 上下文洞察 (" + snap.storage().toUpperCase(java.util.Locale.ROOT) + ")");
        lines.add("│ 会话 ID    : " + snap.sessionId());
        lines.add("│ 当前模型   : " + (snap.model().isEmpty() ? model : snap.model()));
        lines.add("│ Token 水位 : " + snap.estimatedTokens() + " / " + snap.modelContextLimit()
                + " (" + usage + "，达到 " + triggerPct + "% 自动压缩 " + compactPct + "%)");
        lines.add("│ 事件与消息 : 原始事件 " + snap.rawEventCount() + " 条 → 活跃消息 " + snap.activeMessageCount() + " 条");
        lines.add("│ 优化统计   : 已裁剪旧工具输出 " + snap.prunedToolOutputs() + " 处 · 已执行子代理压缩 " + snap.compactionCount() + " 次");
        if (snap.goal() != null && !snap.goal().isBlank()) {
            lines.add("│ 任务目标   : " + snap.goal());
        }
        lines.add("╰ 提示: /compact [比例] 手动压缩 · /goal <目标> 锚定长任务目标");
        printLines(reader, lines);
    }

    /**
     * 处理 {@code /goal <目标>} 命令：锚定当前会话的任务目标，写入事件日志并在后续派生上下文时自动置顶。
     */
    private void updateGoal(LineReader reader, String goal) {
        if (goal.isEmpty()) {
            String current = agent.inspectContext().goal();
            printLines(reader, List.of(current == null || current.isBlank()
                    ? "当前未设置任务目标。用法: /goal <你的核心目标>"
                    : "当前任务目标: " + current));
            return;
        }
        agent.setGoal(goal);
        printLines(reader, List.of("已锚定任务目标: " + goal));
    }

    /**
     * 处理 {@code /resume [序号|会话ID]} 命令：未带参数时列出 SQLite 中最近的历史会话，
     * 带参数时按序号（1 开始）或会话 ID/前缀恢复对应的完整历史上下文。
     */
    private void resumeSession(LineReader reader, String arg) {
        if (agent.isRunning()) {
            printLines(reader, List.of("Agent 正在运行，请等待当前轮结束后再执行 /resume"));
            return;
        }
        List<ContextStore.SessionSummary> recent = agent.listResumableSessions(10);
        if (arg.isEmpty()) {
            if (recent.isEmpty()) {
                printLines(reader, List.of("SQLite 中暂无可恢复的历史会话。"));
                return;
            }
            java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
                    .withZone(java.time.ZoneId.systemDefault());
            List<String> lines = new java.util.ArrayList<>();
            lines.add("╭ 可恢复的历史会话（SQLite .jotaro/jotaro.db）");
            for (int i = 0; i < recent.size(); i++) {
                ContextStore.SessionSummary item = recent.get(i);
                String shortId = item.sessionId().length() > 8 ? item.sessionId().substring(0, 8) : item.sessionId();
                String time = fmt.format(java.time.Instant.ofEpochMilli(item.updatedAt()));
                lines.add("│ [" + (i + 1) + "] " + shortId + "  " + time + "  (" + item.eventCount() + " 条事件)  " + item.title());
            }
            lines.add("╰ 用法: /resume <序号> 或 /resume <会话ID前缀>");
            printLines(reader, lines);
            return;
        }

        String target = arg;
        if (arg.matches("\\d+")) {
            int idx = Integer.parseInt(arg) - 1;
            if (idx < 0 || idx >= recent.size()) {
                printLines(reader, List.of("序号超出范围，请输入 1 到 " + recent.size() + " 之间的数字。"));
                return;
            }
            target = recent.get(idx).sessionId();
        }
        try {
            ContextManager.ContextSnapshot snap = agent.resume(target);
            String shortId = snap.sessionId().length() > 8 ? snap.sessionId().substring(0, 8) : snap.sessionId();
            printLines(reader, List.of("已恢复历史会话 [" + shortId + "]：原始事件 " + snap.rawEventCount()
                    + " 条，活跃消息 " + snap.activeMessageCount() + " 条，当前 Token 估算 "
                    + snap.estimatedTokens() + " / " + snap.modelContextLimit()));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            printLines(reader, List.of("恢复失败: " + exception.getMessage()));
        }
    }

    /**
     * 处理 {@code /compact [比例]} 命令：启动压缩子代理将历史对话按比例（默认 70%）浓缩为摘要。
     */
    private void compactContext(LineReader reader, String ratioArg) {
        if (agent.isRunning()) {
            printLines(reader, List.of("Agent 正在运行，请等待当前轮结束后再执行 /compact"));
            return;
        }
        Double customRatio = null;
        if (!ratioArg.isEmpty()) {
            try {
                customRatio = ContextCompactor.parseRatio(ratioArg, ContextCompactor.DEFAULT_RATIO);
            } catch (IllegalArgumentException exception) {
                printLines(reader, List.of(exception.getMessage()));
                return;
            }
        }
        final Double ratioToUse = customRatio;
        showingRun = true;
        activity = "正在压缩上下文";
        executor.submit(() -> {
            runThread = Thread.currentThread();
            try {
                ContextCompactor.Result result = agent.compact(ratioToUse);
                int percent = (int) Math.round(result.ratio() * 100);
                if (result.compacted()) {
                    printLines(reader, List.of("上下文压缩完成（比例 " + percent + "%）：已将前 "
                            + result.compactedCount() + " 条消息浓缩为摘要（消息 "
                            + result.beforeTotal() + " -> " + result.afterTotal()
                            + "，Token " + result.beforeTokens() + " -> " + result.afterTokens() + "）"));
                } else {
                    printLines(reader, List.of("当前对话消息较少，无需压缩"));
                }
            } catch (RuntimeException exception) {
                printLines(reader, List.of("上下文压缩失败: " + exception.getMessage()));
            } finally {
                Thread.interrupted();
                if (runThread == Thread.currentThread()) {
                    runThread = null;
                }
                synchronized (screen) {
                    showingRun = false;
                    activity = "正在回复";
                    paint(List.of());
                }
            }
        });
    }

    /**
     * 处理 {@code /model} 命令：未带参数时打印可用模型列表，带参数时校验并切换当前会话模型。
     */
    private void switchModel(LineReader reader, String model) {
        if (model.isEmpty()) {
            printLines(reader, List.of("可用模型: " + String.join(", ", registry.keys())));
            return;
        }
        ChatClient chatClient = registry.get(model);
        if (chatClient == null) {
            printLines(reader, List.of("未知模型: " + model + "。可用模型: " + String.join(", ", registry.keys())));
            return;
        }
        try {
            agent.useModel(model, new SpringAiAssistantStreamer(chatClient));
            this.model = model;
            printLines(reader, List.of("已切换到 " + model));
        } catch (IllegalStateException exception) {
            printLines(reader, List.of(exception.getMessage()));
        }
    }

    /**
     * 线程安全地在当前输入框上方打印多行文本。
     */
    private void printLines(LineReader reader, List<String> lines) {
        synchronized (reader) {
            for (String line : lines) {
                reader.printAbove(line);
            }
        }
    }
}
