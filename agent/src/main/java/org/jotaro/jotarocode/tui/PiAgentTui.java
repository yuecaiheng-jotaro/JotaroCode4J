package org.jotaro.jotarocode.tui;

import org.jotaro.jotarocode.agent.PiAgent;
import org.jotaro.jotarocode.agent.runtime.AgentEvent;
import org.jotaro.jotarocode.agent.runtime.AgentEventType;
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
 * 终端里的 Jotaro UI。底部是一框输入，空闲标着 jotaro，运行中标着 steer。
 * 正在生成的文字和子代理的当前动作显示在框上方。子代理按嵌套块展开，而不是等结束再吐出整段结果。
 * 运行结束立刻切回 jotaro。运行中再输入会插进当前轮。
 * 普通文字用上键回溯已发送的内容，下键向前。行首是 {@code /} 时，上下键在候选里移动，回车选定。
 * Ctrl+C 中断当前运行，Ctrl+D 退出。Ctrl+V 粘贴图片或剪贴板文字。斜杠开头的行是命令，不会发给模型，也不进历史。
 * 粘贴超过 5 行或 800 字时，输入框只留一行摘要，发给模型的仍是全文。
 * 执行 bash、powershell、write、edit 之前会发问卷：y 允许这次，a 全部允许，n 拒绝，其他文字当作提示。
 * 输入 {@code /} 后按前缀给出灰色提示。Tab 列出匹配的命令，每条一行；{@code /model} 后再补全模型名。
 */
public final class PiAgentTui {

    private static final String[] SPIN = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    private final PiAgent agent;
    private final ChatClientRegistry registry;
    private volatile String model;
    private final AgentEventFormatter formatter = new AgentEventFormatter();
    private final SubagentPane subagents = new SubagentPane();
    // 模型循环运行在后台，读行线程仍能处理确认、追加指令和中断。
    private final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "pi-agent-tui");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService spinner = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "pi-agent-spin");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean stopped;
    private volatile boolean showingRun;
    private volatile int spin;
    private volatile String activity = "正在回复";
    private volatile Thread runThread;
    private final Object screen = new Object();
    private QuestionGate questions;
    private SingleColumnLineReader reader;

    public PiAgentTui(PiAgent agent, ChatClientRegistry registry, String model) {
        this.agent = agent;
        this.registry = registry;
        this.model = model;
    }

    public void run() throws Exception {
        Terminal terminal = TerminalBuilder.builder().system(true).dumb(true).build();
        reader = new SingleColumnLineReader(terminal);
        reader.setCompleter(new SlashCommandCompleter(() -> registry.keys().stream().sorted().toList()));
        reader.setHistory(new DefaultHistory());
        reader.installInteractionKeys();
        reader.setAutosuggestion(LineReader.SuggestionType.COMPLETER);
        questions = new QuestionGate(lines -> printLines(reader, lines));
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
        agent.abort();
        if (questions != null) {
            questions.cancel();
        }
        executor.shutdownNow();
        spinner.shutdownNow();
    }

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

    private void handle(LineReader reader, String line) {
        if (line.isEmpty()) {
            return;
        }
        if (line.startsWith("/") && !this.reader.containsPaste(line)) {
            handleCommand(reader, line);
            return;
        }
        PasteChunks.Expanded expanded = this.reader.expandPaste(line);
        if (questions != null && questions.isWaiting()) {
            questions.answer(expanded.text());
            return;
        }
        if (agent.isRunning()) {
            printLines(reader, List.of(PromptStyle.user(line), PromptStyle.dim("  插入当前回复")));
            agent.prompt(expanded.text(), this.reader.mediaOf(expanded));
            return;
        }
        printLines(reader, List.of(PromptStyle.user(line)));
        showingRun = true;
        activity = "正在回复";
        executor.submit(() -> {
            runThread = Thread.currentThread();
            try {
                agent.prompt(expanded.text(), this.reader.mediaOf(expanded));
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

    private void onSubagent(SubagentActivity activity) {
        synchronized (screen) {
            paint(subagents.accept(activity));
        }
    }

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

    private void handleCommand(LineReader reader, String line) {
        if ("/help".equals(line)) {
            printLines(reader, List.of(
                    "/help          显示命令",
                    "/exit          退出",
                    "/abort         取消当前运行",
                    "/reset         清空当前会话",
                    "/model         查看模型",
                    "/model <name>  切换模型",
                    "Up / Down       普通文字回溯已发送的内容；/ 后面在候选里上下移动",
                    "Enter           / 后面回车选定。/model 会先补上空格，再选模型名",
                    "Esc / Ctrl+C    候选列表开着时取消选择并收起列表",
                    "Ctrl+C          没有候选列表时中断当前运行",
                    "Ctrl+Z          强制退出",
                    "问卷            bash、powershell、write、edit 执行前询问",
                    "y / a / n       允许这次 / 全部允许 / 拒绝。其他文字当作提示",
                    "Tab             按前缀补全，候选每条一行。灰色提示用 → 或 Ctrl+E 接受",
                    "普通文本        空闲时开始一轮，运行中作为 steering 插入",
                    "粘贴            超过 5 行或 800 字收成 [Pasted text #1 +N lines]，发给模型的是全文",
                    "Ctrl+V          粘贴图片，输入框显示 [Image #1]。Cmd+V 仍粘贴文字"
            ));
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
        if (line.equals("/model") || line.startsWith("/model ")) {
            switchModel(reader, line.substring("/model".length()).trim());
            return;
        }
        printLines(reader, List.of("未知命令: " + line));
    }

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
            agent.useStreamer(new SpringAiAssistantStreamer(chatClient));
            agent.useModel(model);
            this.model = model;
            printLines(reader, List.of("已切换到 " + model));
        } catch (IllegalStateException exception) {
            printLines(reader, List.of(exception.getMessage()));
        }
    }

    private void printLines(LineReader reader, List<String> lines) {
        synchronized (reader) {
            for (String line : lines) {
                reader.printAbove(line);
            }
        }
    }
}
