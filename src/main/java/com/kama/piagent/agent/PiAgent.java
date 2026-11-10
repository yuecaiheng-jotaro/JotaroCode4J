package com.kama.piagent.agent;

import com.kama.piagent.agent.runtime.AgentEvent;
import com.kama.piagent.agent.runtime.AgentEventType;
import com.kama.piagent.agent.runtime.AgentLoop;
import com.kama.piagent.agent.runtime.AgentLoopRequest;
import com.kama.piagent.agent.runtime.AgentTool;
import com.kama.piagent.agent.runtime.AssistantStreamer;
import com.kama.piagent.agent.runtime.PendingMessageQueue;
import com.kama.piagent.agent.runtime.RunControl;
import com.kama.piagent.agent.runtime.ToolExecutionMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * pi-agent-core 的有状态 Agent：持有对话、执行循环、接收 steering 和 follow-up。
 */
@Slf4j
public class PiAgent {

    private final String systemPrompt;
    private final List<AgentTool> tools;
    private final int maxSteps;
    private final int messageWindow;
    private final ToolExecutionMode toolExecution;
    private final List<Message> messages = new ArrayList<>();
    private final PendingMessageQueue steeringQueue = new PendingMessageQueue();
    private final PendingMessageQueue followUpQueue = new PendingMessageQueue();
    private final List<Consumer<AgentEvent>> listeners = new CopyOnWriteArrayList<>();
    private final Object runLock = new Object();

    private AssistantStreamer streamer;
    private volatile RunControl runControl = new RunControl();
    private volatile boolean running;
    private volatile AgentState state = AgentState.IDLE;

    public PiAgent(String systemPrompt,
                   List<AgentTool> tools,
                   AssistantStreamer streamer,
                   int maxSteps,
                   int messageWindow,
                   ToolExecutionMode toolExecution) {
        this.systemPrompt = systemPrompt;
        this.tools = List.copyOf(tools == null ? List.of() : tools);
        this.streamer = streamer;
        this.maxSteps = maxSteps;
        this.messageWindow = messageWindow;
        this.toolExecution = toolExecution == null ? ToolExecutionMode.PARALLEL : toolExecution;
        if (StringUtils.hasText(systemPrompt)) {
            messages.add(new SystemMessage(systemPrompt));
        }
    }

    public void subscribe(Consumer<AgentEvent> listener) {
        listeners.add(listener);
    }

    public void useStreamer(AssistantStreamer streamer) {
        synchronized (runLock) {
            if (running) {
                throw new IllegalStateException("Agent 正在运行，不能切换模型");
            }
            this.streamer = streamer;
        }
    }

    public boolean isRunning() {
        return running;
    }

    public AgentState getState() {
        return state;
    }

    public List<Message> getMessages() {
        synchronized (runLock) {
            return List.copyOf(messages);
        }
    }

    /**
     * 空闲时开始一轮。运行中则把文本放进 steering 队列。
     */
    public void prompt(String text) {
        synchronized (runLock) {
            if (running) {
                steeringQueue.enqueue(userMessage(text));
                return;
            }
            running = true;
            state = AgentState.THINKING;
            messages.add(userMessage(text));
        }
        executeLoop();
    }

    /**
     * 空闲时等同于 prompt。运行中则等到本轮即将结束再插入。
     */
    public void followUp(String text) {
        synchronized (runLock) {
            if (running) {
                followUpQueue.enqueue(userMessage(text));
                return;
            }
            running = true;
            state = AgentState.THINKING;
            messages.add(userMessage(text));
        }
        executeLoop();
    }

    public void abort() {
        runControl.abort();
    }

    public void reset() {
        synchronized (runLock) {
            if (running) {
                throw new IllegalStateException("Agent 正在运行，不能重置");
            }
            messages.clear();
            if (StringUtils.hasText(systemPrompt)) {
                messages.add(new SystemMessage(systemPrompt));
            }
            steeringQueue.clear();
            followUpQueue.clear();
            state = AgentState.IDLE;
        }
    }

    private void executeLoop() {
        RunControl control = new RunControl();
        this.runControl = control;
        try {
            List<Message> snapshot;
            synchronized (runLock) {
                snapshot = new ArrayList<>(messages);
            }
            new AgentLoop(AgentLoopRequest.builder()
                    .messages(snapshot)
                    .tools(tools)
                    .streamer(streamer)
                    .maxSteps(maxSteps)
                    .modelWindow(messageWindow)
                    .toolExecution(toolExecution)
                    .steeringMessages(steeringQueue::drain)
                    .followUpMessages(followUpQueue::drain)
                    .control(control)
                    .build(), this::dispatch).run();
        } catch (RuntimeException exception) {
            state = AgentState.ERROR;
            log.error("Agent 运行失败", exception);
            throw exception;
        } finally {
            synchronized (runLock) {
                running = false;
                if (state != AgentState.ERROR) {
                    state = AgentState.FINISHED;
                }
            }
        }
    }

    private void dispatch(AgentEvent event) {
        if (event.getType() == AgentEventType.MESSAGE_END && event.getMessage() != null) {
            synchronized (runLock) {
                messages.add(event.getMessage());
            }
        }
        switch (event.getType()) {
            case AGENT_START, TURN_START -> state = AgentState.THINKING;
            case TOOL_EXECUTION_START -> state = AgentState.EXECUTING;
            case MESSAGE_END -> {
                if (Boolean.TRUE.equals(event.getError())) {
                    state = AgentState.ERROR;
                }
            }
            case AGENT_END -> {
                if (state != AgentState.ERROR) {
                    state = AgentState.FINISHED;
                }
            }
            default -> {
            }
        }
        for (Consumer<AgentEvent> listener : listeners) {
            listener.accept(event);
        }
    }

    private static UserMessage userMessage(String text) {
        return new UserMessage(text == null ? "" : text);
    }
}
