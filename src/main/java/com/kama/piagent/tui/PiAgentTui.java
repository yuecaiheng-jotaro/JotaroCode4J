package com.kama.piagent.tui;

import com.kama.piagent.agent.PiAgent;
import com.kama.piagent.agent.runtime.SpringAiAssistantStreamer;
import com.kama.piagent.config.ChatClientRegistry;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 终端里的 Pi Agent：空闲时输入会开始一轮，运行中输入会作为 steering 插入。
 */
public final class PiAgentTui {

    private final PiAgent agent;
    private final ChatClientRegistry registry;
    private final AgentEventFormatter formatter = new AgentEventFormatter();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "pi-agent-tui");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean stopped;

    public PiAgentTui(PiAgent agent, ChatClientRegistry registry) {
        this.agent = agent;
        this.registry = registry;
    }

    public void run() throws Exception {
        Terminal terminal = TerminalBuilder.builder().system(true).dumb(true).build();
        LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
        agent.subscribe(event -> printLines(reader, formatter.format(event)));
        printLines(reader, List.of("Pi Agent 已启动。输入 /help 查看命令。"));
        while (!stopped) {
            String line;
            try {
                line = reader.readLine(agent.isRunning() ? "steer> " : "pi> ");
            } catch (UserInterruptException exception) {
                agent.abort();
                printLines(reader, List.of("已请求取消"));
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
        executor.submit(() -> agent.prompt(line));
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
            agent.abort();
            printLines(reader, List.of(agent.isRunning() ? "已请求取消" : "当前没有运行中的任务"));
            return;
        }
        if ("/reset".equals(line)) {
            try {
                agent.reset();
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
