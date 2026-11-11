package org.jotaro.jotarocode.agent;

import org.jotaro.jotarocode.agent.runtime.AgentEvent;
import org.jotaro.jotarocode.agent.runtime.AgentEventType;
import org.jotaro.jotarocode.agent.runtime.AgentLoop;
import org.jotaro.jotarocode.agent.runtime.AgentLoopRequest;
import org.jotaro.jotarocode.agent.runtime.AgentTool;
import org.jotaro.jotarocode.agent.runtime.AssistantStreamer;
import org.jotaro.jotarocode.agent.runtime.BeforeToolCallResult;
import org.jotaro.jotarocode.agent.runtime.PendingMessageQueue;
import org.jotaro.jotarocode.agent.runtime.RunControl;
import org.jotaro.jotarocode.agent.runtime.ToolExecutionMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 一个会话的有状态外壳。自己保存消息列表和运行标记，把一轮具体怎么跑交给 {@link org.jotaro.jotarocode.agent.runtime.AgentLoop}。
 *
 * <p>空闲时 {@link #prompt(String)} 立刻开一轮。运行中再调用它，文本进入 steering，
 * 当前这轮工具跑完后插进对话。{@link #followUp(String)} 则等到循环快停时才插入。
 * 循环拿到的是消息快照；{@code MESSAGE_END} 再把新消息追加回这里，所以循环内部的修改不会直接改这份列表。
 */
@Slf4j
public class PiAgent {

    /**
     * 系统提示词
     */
    private final String systemPrompt;

    /**
     * 可用工具集
     */
    private final List<AgentTool> tools;

    /**
     * 最大执行步长
     */
    private final int maxSteps;
    private final int messageWindow;

    /**
     * 工具执行模式
     */
    private final ToolExecutionMode toolExecution;

    /**
     * 完整的会话历史
     */
    private final List<Message> messages = new ArrayList<>();

    /**
     * steering 等待队列,tui目前使用的队列
     */
    private final PendingMessageQueue steeringQueue = new PendingMessageQueue();

    /**
     * follow队列，如果只用一个队列，和steeringQueue功能类似
     */
    private final PendingMessageQueue followUpQueue = new PendingMessageQueue();

    /**
     * 监听这个启动的hook触发队列
     */
    private final List<Consumer<AgentEvent>> listeners = new CopyOnWriteArrayList<>();

    /**
     * agent级别的chat锁
     */
    private final Object runLock = new Object();

    private AssistantStreamer streamer;

    /**
     * agent运行时线程管理器
     */
    private volatile RunControl runControl = new RunControl();
    private volatile boolean running;
    private volatile AgentState state = AgentState.IDLE;
    private volatile Function<AgentLoopRequest.BeforeToolCallContext, BeforeToolCallResult> beforeToolCall;

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

    public void setBeforeToolCall(Function<AgentLoopRequest.BeforeToolCallContext, BeforeToolCallResult> beforeToolCall) {
        this.beforeToolCall = beforeToolCall;
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
        if (!beginRun(text, false)) {
            return;
        }
        executeLoop();
    }

    /**
     * 空闲时等同于 prompt。运行中则等到本轮即将结束再插入。
     */
    public void followUp(String text) {
        if (!beginRun(text, true)) {
            return;
        }
        executeLoop();
    }

    /**
     * 取消开关在离开锁之前就换上。否则 Ctrl+C 可能打在旧的 {@link RunControl} 上，新的一轮收不到。
     * @return false 表示这次文本只是插进了正在跑的队列
     */
    private boolean beginRun(String text, boolean followUp) {
        synchronized (runLock) {
            if (running) {
                (followUp ? followUpQueue : steeringQueue).enqueue(userMessage(text));
                return false;
            }
            running = true;
            state = AgentState.THINKING;
            messages.add(userMessage(text));
            runControl = new RunControl();
            return true;
        }
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
        RunControl control = this.runControl;
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
                    .beforeToolCall(beforeToolCall)
                    .build(), this::dispatch).run();
        } catch (RuntimeException e) {
            state = AgentState.ERROR;
            log.error("Agent 运行失败", e);
            throw e;
        } finally {
            // abort 会打断工作线程。标记留着的话，这条线程上下一次运行会立刻被中断。
            Thread.interrupted();
            synchronized (runLock) {
                running = false;
                if (state != AgentState.ERROR) {
                    state = AgentState.FINISHED;
                }
            }
        }
    }

    /** 循环每发出一条完整消息，就追加进会话历史，再转发给页面或终端。 */
    private void dispatch(AgentEvent event) {
        if (event.getType() == AgentEventType.MESSAGE_END && event.getMessage() != null) {
            synchronized (runLock) {
                messages.add(event.getMessage());
            }
        }
        // 状态机转换
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

        // 事件hook，触发一遍所有监听agent事件的函数
        listeners.forEach(listener -> listener.accept(event));

    }

    private static UserMessage userMessage(String text) {
        return new UserMessage(text == null ? "" : text);
    }
}
