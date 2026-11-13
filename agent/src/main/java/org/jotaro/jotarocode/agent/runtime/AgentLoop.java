package org.jotaro.jotarocode.agent.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Spring AI 上的 agent 循环，语义如下：
 * 每一轮是一次模型响应加上它发起的工具调用；工具可以顺序或并行执行；
 * steering 在当前轮结束后插入，follow-up 在循环即将停止时插入。
 */
@Slf4j
public final class AgentLoop {

    private final AgentLoopRequest request;
    private final Consumer<AgentEvent> listener;
    private final List<Message> messages;
    private final RunControl control;
    private final ToolBatchExecutor toolExecutor;

    public AgentLoop(AgentLoopRequest request, Consumer<AgentEvent> listener) {
        this.request = request;
        this.listener = listener;
        this.messages = new ArrayList<>(request.getMessages() == null ? List.of() : request.getMessages());
        this.control = request.getControl() == null ? new RunControl() : request.getControl();
        this.toolExecutor = new ToolBatchExecutor(request, control, this::emit);
    }

    public void run() {
        RunControl previous = RunControl.install(control);
        try {
            doRun();
        } finally {
            RunControl.install(previous);
        }
    }

    private void doRun() {
        List<Message> pending = drain(request.getSteeringMessages());
        emit(AgentEvent.of(AgentEventType.AGENT_START));

        for (int steps = 1; ; steps++) {
            emit(AgentEvent.of(AgentEventType.TURN_START));
            appendPending(pending);
            if (control.isAborted()) {
                finish(false);
                return;
            }

            AssistantTurn turn = streamAssistant();
            boolean failed = turn.getStopReason() == StopReason.ERROR || turn.getStopReason() == StopReason.ABORTED;
            messages.add(turn.getMessage());
            emit(AgentEvent.builder()
                    .type(AgentEventType.MESSAGE_END)
                    .message(turn.getMessage())
                    .error(failed)
                    .build());

            ToolBatchExecutor.ExecutedBatch batch = failed
                    ? new ToolBatchExecutor.ExecutedBatch(List.of(), true)
                    : toolExecutor.execute(turn);
            appendToolResults(batch.responses());

            TurnDecision decision = invokeFinishTurn(turn);
            emitTurnEnd(turn, batch.responses());
            if (failed || decision == TurnDecision.END || control.isAborted()) {
                finish(false);
                return;
            }
            if (request.getMaxSteps() > 0 && steps >= request.getMaxSteps()) {
                log.warn("达到最大步数 {}，停止 Agent", request.getMaxSteps());
                finish(true);
                return;
            }

            pending = drain(request.getSteeringMessages());
            if (!batch.terminate() || !pending.isEmpty()) {
                continue;
            }
            pending = drain(request.getFollowUpMessages());
            if (pending.isEmpty() && decision != TurnDecision.CONTINUE) {
                finish(false);
                return;
            }
        }
    }

    private void appendToolResults(List<ToolResponseMessage.ToolResponse> results) {
        if (results.isEmpty()) {
            return;
        }
        ToolResponseMessage message = ToolResponseMessage.builder().responses(results).build();
        messages.add(message);
        emit(AgentEvent.builder()
                .type(AgentEventType.MESSAGE_END)
                .message(message)
                .toolResults(results)
                .build());
    }

    private void finish(boolean stepLimitReached) {
        emit(AgentEvent.builder()
                .type(AgentEventType.AGENT_END)
                .stepLimitReached(stepLimitReached)
                .build());
    }

    private void emitText(String delta, boolean reasoning) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        emit(AgentEvent.builder()
                .type(AgentEventType.MESSAGE_UPDATE)
                .textDelta(delta)
                .reasoning(reasoning)
                .build());
    }

    private AssistantTurn streamAssistant() {
        emit(AgentEvent.builder()
                .type(AgentEventType.MESSAGE_START)
                .message(new AssistantMessage(""))
                .build());
        try {
            AssistantTurn turn = request.getStreamer().stream(
                    visibleMessages(),
                    request.getTools(),
                    request.getSystemPrompt(),
                    control,
                    delta -> emitText(delta, false),
                    delta -> emitText(delta, true)
            );
            if (turn == null || turn.getMessage() == null) {
                return AssistantTurn.error("模型没有返回内容");
            }
            return turn;
        } catch (RuntimeException exception) {
            if (control.isAborted()) {
                return AssistantTurn.aborted("");
            }
            log.error("模型调用失败", exception);
            return AssistantTurn.error(exception.getMessage() == null ? "模型调用失败" : exception.getMessage());
        }
    }

    /**
     * 发给模型的窗口。超长时保留开头的系统提示，并从窗口起点往前挪，
     * 避免一条工具结果落在窗口里、对应的助手工具调用却被裁掉。
     * todo 做上下文压缩
     */
    private List<Message> visibleMessages() {
        int window = request.getModelWindow();
        if (window <= 0 || messages.size() <= window) {
            return List.copyOf(messages);
        }
        int start = messages.size() - window;
        // 工具响应需要前面的助手调用才能配对，因此窗口起点可以向前扩一条。
        while (start > 0 && messages.get(start) instanceof ToolResponseMessage) {
            start--;
        }
        List<Message> visible = new ArrayList<>();
        if (start > 0 && messages.get(0) instanceof SystemMessage systemMessage) {
            visible.add(systemMessage);
        }
        visible.addAll(messages.subList(start, messages.size()));
        return visible;
    }

    private void appendPending(List<Message> pending) {
        for (Message message : pending) {
            messages.add(message);
            emit(AgentEvent.builder().type(AgentEventType.MESSAGE_START).message(message).build());
            emit(AgentEvent.builder().type(AgentEventType.MESSAGE_END).message(message).build());
        }
    }

    private void emitTurnEnd(AssistantTurn turn, List<ToolResponseMessage.ToolResponse> toolResults) {
        emit(AgentEvent.builder()
                .type(AgentEventType.TURN_END)
                .message(turn.getMessage())
                .toolResults(toolResults)
                .error(turn.getStopReason() == StopReason.ERROR || turn.getStopReason() == StopReason.ABORTED)
                .build());
    }

    private TurnDecision invokeFinishTurn(AssistantTurn turn) {
        if (request.getFinishTurn() == null) {
            return null;
        }
        try {
            TurnDecision decision = request.getFinishTurn().apply(new AgentLoopRequest.TurnContext(
                    turn.getMessage(),
                    turn.getStopReason(),
                    List.copyOf(messages)
            ));
            if (turn.getStopReason() == StopReason.ERROR || turn.getStopReason() == StopReason.ABORTED) {
                return null;
            }
            return decision;
        } catch (RuntimeException exception) {
            log.warn("finishTurn 执行失败: {}", exception.getMessage());
            return null;
        }
    }

    private void emit(AgentEvent event) {
        synchronized (listener) {
            listener.accept(event);
        }
    }

    private static List<Message> drain(java.util.function.Supplier<List<Message>> supplier) {
        if (supplier == null) {
            return List.of();
        }
        List<Message> drained = supplier.get();
        return drained == null ? List.of() : List.copyOf(drained);
    }

}
