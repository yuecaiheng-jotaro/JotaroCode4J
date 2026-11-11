package org.jotaro.jotarocode.agent.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Spring AI 上的 agent 循环，语义对齐 pi-agent-core：
 * 每一轮是一次模型响应加上它发起的工具调用；工具可以顺序或并行执行；
 * steering 在当前轮结束后插入，follow-up 在循环即将停止时插入。
 */
@Slf4j
public final class AgentLoop {

    private final AgentLoopRequest request;
    private final Consumer<AgentEvent> listener;
    private final List<Message> messages;
    private final RunControl control;

    public AgentLoop(AgentLoopRequest request, Consumer<AgentEvent> listener) {
        this.request = request;
        this.listener = listener;
        this.messages = new ArrayList<>(request.getMessages() == null ? List.of() : request.getMessages());
        this.control = request.getControl() == null ? new RunControl() : request.getControl();
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
        // 开跑前先取走已经排队的 steering，避免用户在启动瞬间插的话被丢掉。一次只取一条消息
        List<Message> pending = new ArrayList<>(drain(request.getSteeringMessages()));

        // 【hook-1】turn开始前的hook，进入loop前
        emit(AgentEvent.of(AgentEventType.AGENT_START));

        // finishTurn 返回 CONTINUE 时，即使没有工具也要再向模型要一轮。
        boolean explicitContinue = false;
        while (true) {
            // 内层持续到：模型不再要工具，并且没有待插入的 steering。
            boolean hasMoreToolCalls = true;
            while (hasMoreToolCalls || !pending.isEmpty()) {
                // 【hook-2】loop中，每个turn开始前
                emit(AgentEvent.of(AgentEventType.TURN_START));

                // 放入loop的message里，即当前react循环里的消息
                appendPending(pending);
                pending = new ArrayList<>();

                if (control.isAborted()) {
                    finish(false);
                    return;
                }
                if (request.getMaxSteps() > 0 && countAssistantMessages() >= request.getMaxSteps()) {
                    log.warn("达到最大步数 {}，停止 Agent", request.getMaxSteps());
                    finish(true);
                    return;
                }
                // 处理当前turn
                AssistantTurn turn = streamAssistant();

                // 处理完当前turn，内部存置loop的列表里
                messages.add(turn.getMessage());
                // hook 留置位
                emit(AgentEvent.builder()
                        .type(AgentEventType.MESSAGE_END)
                        .message(turn.getMessage())
                        .error(turn.getStopReason() == StopReason.ERROR || turn.getStopReason() == StopReason.ABORTED)
                        .build());

                if (turn.getStopReason() == StopReason.ERROR || turn.getStopReason() == StopReason.ABORTED) {
                    invokeFinishTurn(turn);
                    emitTurnEnd(turn, List.of());
                    finish(false);
                    return;
                }

                List<AssistantMessage.ToolCall> toolCalls = turn.getMessage().getToolCalls();
                List<ToolResponseMessage.ToolResponse> toolResults = List.of();
                hasMoreToolCalls = false;
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    ExecutedBatch batch = turn.getStopReason() == StopReason.LENGTH
                            ? failTruncated(toolCalls)
                            : executeToolCalls(turn.getMessage(), toolCalls);
                    toolResults = batch.responses;
                    hasMoreToolCalls = !batch.terminate;
                    if (!toolResults.isEmpty()) {
                        ToolResponseMessage toolMessage = ToolResponseMessage.builder()
                                .responses(toolResults)
                                .build();
                        messages.add(toolMessage);
                        emit(AgentEvent.builder()
                                .type(AgentEventType.MESSAGE_END)
                                .message(toolMessage)
                                .toolResults(toolResults)
                                .build());
                    }
                }

                TurnDecision decision = invokeFinishTurn(turn);
                emitTurnEnd(turn, toolResults);
                if (decision == TurnDecision.END) {
                    finish(false);
                    return;
                }
                explicitContinue = decision == TurnDecision.CONTINUE;

                if (control.isAborted()) {
                    finish(false);
                    return;
                }
                if (request.getMaxSteps() > 0 && countAssistantMessages() >= request.getMaxSteps()) {
                    log.warn("达到最大步数 {}，停止 Agent", request.getMaxSteps());
                    finish(true);
                    return;
                }

                pending = new ArrayList<>(drain(request.getSteeringMessages()));
                if (hasMoreToolCalls || !pending.isEmpty()) {
                    explicitContinue = false;
                }
            }

            // 内层停了才看 follow-up。有的话当作新的用户消息，回到外层再开一轮。
            List<Message> followUps = drain(request.getFollowUpMessages());
            if (!followUps.isEmpty()) {
                explicitContinue = false;
                pending = new ArrayList<>(followUps);
                continue;
            }
            if (explicitContinue) {
                explicitContinue = false;
                continue;
            }
            break;
        }
        finish(false);
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

    private ExecutedBatch executeToolCalls(AssistantMessage assistantMessage, List<AssistantMessage.ToolCall> toolCalls) {
        // 默认并行。只要有一个工具声明顺序执行，这一整批都改成顺序，避免后面的调用依赖还没写完的文件。
        boolean sequential = request.getToolExecution() != ToolExecutionMode.PARALLEL || toolCalls.stream().anyMatch(call -> {
            AgentTool tool = findTool(call.name());
            return tool != null && tool.executionMode() == ToolExecutionMode.SEQUENTIAL;
        });
        ToolOutcome[] slots = new ToolOutcome[toolCalls.size()];
        List<Integer> readyIndexes = new ArrayList<>();
        List<PreparedCall> ready = new ArrayList<>();

        for (int index = 0; index < toolCalls.size(); index++) {
            AssistantMessage.ToolCall toolCall = toolCalls.get(index);
            emit(AgentEvent.builder()
                    .type(AgentEventType.TOOL_EXECUTION_START)
                    .toolCallId(toolCall.id())
                    .toolName(toolCall.name())
                    .arguments(toolCall.arguments())
                    .build());
            if (control.isAborted()) {
                slots[index] = abortedOutcome(toolCall);
                emitEnd(slots[index]);
                continue;
            }
            PreparedCall preparation = prepare(assistantMessage, toolCall);
            if (preparation.outcome != null) {
                slots[index] = preparation.outcome;
                emitEnd(slots[index]);
                continue;
            }
            readyIndexes.add(index);
            ready.add(preparation);
        }

        List<ToolOutcome> executed = sequential ? executeSequential(ready) : executeParallel(ready);
        for (int index = 0; index < executed.size() && index < readyIndexes.size(); index++) {
            slots[readyIndexes.get(index)] = executed.get(index);
        }
        for (int index = 0; index < slots.length; index++) {
            if (slots[index] == null) {
                slots[index] = abortedOutcome(toolCalls.get(index));
                emitEnd(slots[index]);
            }
        }

        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        boolean terminate = slots.length > 0;
        for (ToolOutcome outcome : slots) {
            responses.add(outcome.response);
            terminate = terminate && outcome.terminate;
        }
        // 名字是 terminate 的工具无条件结束循环，即使钩子没有把 terminate 设为 true。
        if (responses.stream().anyMatch(response -> "terminate".equals(response.name()))) {
            terminate = true;
        }
        return new ExecutedBatch(responses, terminate);
    }

    private List<ToolOutcome> executeSequential(List<PreparedCall> prepared) {
        List<ToolOutcome> outcomes = new ArrayList<>();
        boolean aborted = false;
        for (PreparedCall call : prepared) {
            if (aborted || control.isAborted()) {
                ToolOutcome abortedOutcome = abortedOutcome(call.toolCall);
                emitEnd(abortedOutcome);
                outcomes.add(abortedOutcome);
                aborted = true;
                continue;
            }
            ToolOutcome outcome = runAndFinalize(call);
            emitEnd(outcome);
            outcomes.add(outcome);
        }
        return outcomes;
    }

    private List<ToolOutcome> executeParallel(List<PreparedCall> prepared) {
        List<CompletableFuture<ToolOutcome>> futures = new ArrayList<>();
        for (PreparedCall call : prepared) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                RunControl previous = RunControl.install(control);
                try {
                    if (control.isAborted()) {
                        ToolOutcome aborted = abortedOutcome(call.toolCall);
                        emitEnd(aborted);
                        return aborted;
                    }
                    ToolOutcome outcome = runAndFinalize(call);
                    emitEnd(outcome);
                    return outcome;
                } finally {
                    RunControl.install(previous);
                }
            }));
        }
        List<ToolOutcome> outcomes = new ArrayList<>();
        for (CompletableFuture<ToolOutcome> future : futures) {
            outcomes.add(future.join());
        }
        return outcomes;
    }

    private PreparedCall prepare(AssistantMessage assistantMessage, AssistantMessage.ToolCall toolCall) {
        AgentTool tool = findTool(toolCall.name());
        if (tool == null) {
            return PreparedCall.immediate(errorOutcome(toolCall, "工具 " + toolCall.name() + " 不存在", false));
        }
        if (control.isAborted()) {
            return PreparedCall.immediate(abortedOutcome(toolCall));
        }
        if (request.getBeforeToolCall() != null) {
            try {
                BeforeToolCallResult before = request.getBeforeToolCall().apply(
                        new AgentLoopRequest.BeforeToolCallContext(assistantMessage, toolCall, toolCall.arguments())
                );
                if (before != null && before.isBlock()) {
                    String reason = before.getReason() == null || before.getReason().isBlank()
                            ? "工具执行被拦截"
                            : before.getReason();
                    return PreparedCall.immediate(errorOutcome(toolCall, reason, before.isTerminate()));
                }
            } catch (RuntimeException exception) {
                return PreparedCall.immediate(errorOutcome(toolCall, exception.getMessage(), false));
            }
        }
        return PreparedCall.ready(assistantMessage, toolCall, tool);
    }

    private ToolOutcome runAndFinalize(PreparedCall prepared) {
        long startedAt = System.nanoTime();
        String result;
        boolean error = false;
        try {
            String output = prepared.tool.execute(prepared.toolCall.arguments());
            result = output == null ? "" : output;
        } catch (RuntimeException exception) {
            error = true;
            result = exception.getMessage() == null ? "工具执行失败" : exception.getMessage();
        }
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        boolean terminate = "terminate".equals(prepared.tool.name());
        if (request.getAfterToolCall() != null) {
            try {
                AfterToolCallResult after = request.getAfterToolCall().apply(new AgentLoopRequest.AfterToolCallContext(
                        prepared.assistantMessage,
                        prepared.toolCall,
                        prepared.toolCall.arguments(),
                        result,
                        error
                ));
                if (after != null) {
                    if (after.getContent() != null) {
                        result = after.getContent();
                    }
                    if (after.getError() != null) {
                        error = after.getError();
                    }
                    if (after.getTerminate() != null) {
                        terminate = after.getTerminate();
                    }
                }
            } catch (RuntimeException exception) {
                error = true;
                result = exception.getMessage() == null ? "工具结果处理失败" : exception.getMessage();
                terminate = false;
            }
        }
        return new ToolOutcome(response(prepared.toolCall, result), error, terminate, durationMs);
    }

    private ExecutedBatch failTruncated(List<AssistantMessage.ToolCall> toolCalls) {
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            emit(AgentEvent.builder()
                    .type(AgentEventType.TOOL_EXECUTION_START)
                    .toolCallId(toolCall.id())
                    .toolName(toolCall.name())
                    .arguments(toolCall.arguments())
                    .build());
            ToolOutcome outcome = errorOutcome(
                    toolCall,
                    "工具 " + toolCall.name() + " 未执行：模型输出达到长度上限，参数可能不完整。请重新发起完整的工具调用。",
                    false
            );
            emitEnd(outcome);
            responses.add(outcome.response);
        }
        return new ExecutedBatch(responses, false);
    }

    private void emitEnd(ToolOutcome outcome) {
        emit(AgentEvent.builder()
                .type(AgentEventType.TOOL_EXECUTION_END)
                .toolCallId(outcome.response.id())
                .toolName(outcome.response.name())
                .result(outcome.response.responseData())
                .error(outcome.error)
                .durationMs(outcome.durationMs)
                .build());
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

    private AgentTool findTool(String name) {
        if (request.getTools() == null || name == null) {
            return null;
        }
        for (AgentTool tool : request.getTools()) {
            if (name.equals(tool.name())) {
                return tool;
            }
        }
        return null;
    }

    private int countAssistantMessages() {
        int count = 0;
        for (Message message : messages) {
            if (message instanceof AssistantMessage) {
                count++;
            }
        }
        return count;
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
        return drained == null ? List.of() : drained;
    }

    private static ToolOutcome errorOutcome(AssistantMessage.ToolCall toolCall, String message, boolean terminate) {
        return new ToolOutcome(response(toolCall, message), true, terminate, null);
    }

    private static ToolOutcome abortedOutcome(AssistantMessage.ToolCall toolCall) {
        return errorOutcome(toolCall, "操作已取消", false);
    }

    private static ToolResponseMessage.ToolResponse response(AssistantMessage.ToolCall toolCall, String data) {
        return new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), data == null ? "" : data);
    }

    private record PreparedCall(AssistantMessage assistantMessage,
                                AssistantMessage.ToolCall toolCall,
                                AgentTool tool,
                                ToolOutcome outcome) {
        static PreparedCall ready(AssistantMessage assistantMessage, AssistantMessage.ToolCall toolCall, AgentTool tool) {
            return new PreparedCall(assistantMessage, toolCall, tool, null);
        }

        static PreparedCall immediate(ToolOutcome outcome) {
            return new PreparedCall(null, null, null, outcome);
        }
    }

    private record ToolOutcome(ToolResponseMessage.ToolResponse response, boolean error, boolean terminate, Long durationMs) {
    }

    private record ExecutedBatch(List<ToolResponseMessage.ToolResponse> responses, boolean terminate) {
    }
}
