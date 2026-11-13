package org.jotaro.jotarocode.tui;

import org.jotaro.jotarocode.agent.PiAgent;
import org.jotaro.jotarocode.agent.runtime.AgentEvent;
import org.jotaro.jotarocode.agent.runtime.AgentEventType;
import org.jotaro.jotarocode.agent.runtime.SpringAiAssistantStreamer;
import org.jotaro.jotarocode.config.ChatClientRegistry;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 终端里的 Jotaro UI。空闲提示符是 {@code ◆ jotaro ❯}，运行中是 {@code ◆ steer ❯}，这时输入会插进当前轮。
 * 正在生成的半行显示在输入框上方，中间空一行；运行结束立刻切回 jotaro，不用再按一次回车。
 * 模型名显示在行尾。普通文字用上键回溯已发送的内容，下键向前。行首是 {@code /} 时，上下键在候选里移动，回车选定。
 * Ctrl+C 中断当前运行，Ctrl+D 退出。斜杠开头的行是命令，不会发给模型，也不进历史。
 * 执行 bash、powershell、write、edit 之前会发问卷：y 允许这次，a 全部允许，n 拒绝，其他文字当作提示。
 * 输入 {@code /} 后按前缀给出灰色提示。Tab 列出匹配的命令，每条一行；{@code /model} 后再补全模型名。
 */
public final class PiAgentTui {

    private final PiAgent agent;
    private final ChatClientRegistry registry;
    private volatile String model;
    private final AgentEventFormatter formatter = new AgentEventFormatter();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "pi-agent-tui");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean stopped;
    private volatile boolean showingRun;
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
        agent.subscribe(this::onEvent);
        printLines(reader, List.of(PromptStyle.banner()));
        while (!stopped) {
            String line;
            try {
                line = reader.readLine(currentFrame(), PromptStyle.right(model), (Character) null, null);
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
    }

    private void handle(LineReader reader, String line) {
        if (line.isEmpty()) {
            return;
        }
        if (line.startsWith("/")) {
            handleCommand(reader, line);
            return;
        }
        if (questions != null && questions.isWaiting()) {
            questions.answer(line);
            return;
        }
        if (agent.isRunning()) {
            agent.prompt(line);
            return;
        }
        showingRun = true;
        activity = "正在回复";
        executor.submit(() -> {
            runThread = Thread.currentThread();
            try {
                agent.prompt(line);
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
            if (event.getType() == AgentEventType.TOOL_EXECUTION_START) {
                activity = "正在执行 " + (event.getToolName() == null ? "工具" : event.getToolName());
            } else if (event.getType() == AgentEventType.MESSAGE_UPDATE
                    || event.getType() == AgentEventType.TOOL_EXECUTION_END
                    || event.getType() == AgentEventType.TURN_START) {
                activity = "正在回复";
            }
            paint(formatter.format(event));
        }
    }

    private void paint(List<String> lines) {
        if (reader == null) {
            return;
        }
        boolean running = showingRun || agent.isRunning();
        String live = PromptStyle.tail(formatter.livePlain(), reader.columns());
        reader.present(lines, PromptStyle.frame(running, live, formatter.liveThinking(), activity), PromptStyle.right(model));
    }

    private String currentFrame() {
        synchronized (screen) {
            boolean running = showingRun || agent.isRunning();
            String live = reader == null ? "" : PromptStyle.tail(formatter.livePlain(), reader.columns());
            return PromptStyle.frame(running, live, formatter.liveThinking(), activity).replace("%", "%%");
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
                    "普通文本        空闲时开始一轮，运行中作为 steering 插入"
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
