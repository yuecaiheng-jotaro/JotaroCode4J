package org.jotaro.jotarocode.agent.runtime;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** 处理一次模型回复中的工具调用，按调用顺序返回结果。 */
final class ToolBatchExecutor {

    private final AgentLoopRequest request;
    private final RunControl control;
    private final Consumer<AgentEvent> emit;
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    ToolBatchExecutor(AgentLoopRequest request, RunControl control, Consumer<AgentEvent> emit) {
        this.request = request;
        this.control = control;
        this.emit = emit;
        if (request.getTools() != null) {
            for (AgentTool tool : request.getTools()) {
                if (tool.name() != null) {
                    tools.putIfAbsent(tool.name(), tool);
                }
            }
        }
    }

    ExecutedBatch execute(AssistantTurn turn) {
        AssistantMessage message = turn.getMessage();
        List<AssistantMessage.ToolCall> calls = message.getToolCalls();
        if (calls == null || calls.isEmpty()) {
            return new ExecutedBatch(List.of(), true);
        }
        return turn.getStopReason() == StopReason.LENGTH
                ? failTruncated(calls)
                : executeToolCalls(message, calls);
    }

    private ExecutedBatch executeToolCalls(AssistantMessage assistantMessage, List<AssistantMessage.ToolCall> toolCalls) {
        // 默认并行。只要有一个工具声明顺序执行，这一整批都改成顺序，避免后面的调用依赖还没写完的文件。
        boolean sequential = request.getToolExecution() != ToolExecutionMode.PARALLEL || toolCalls.stream().anyMatch(call -> {
            AgentTool tool = tools.get(call.name());
            return tool != null && tool.executionMode() == ToolExecutionMode.SEQUENTIAL;
        });
        // 工具完成事件可以乱序到达，回给模型的结果按原始调用位置组装。
        ToolOutcome[] slots = new ToolOutcome[toolCalls.size()];
        List<Integer> readyIndexes = new ArrayList<>();
        List<PreparedCall> ready = new ArrayList<>();

        for (int index = 0; index < toolCalls.size(); index++) {
            AssistantMessage.ToolCall toolCall = toolCalls.get(index);
            emitStart(toolCall);
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
        for (int index = 0; index < executed.size(); index++) {
            slots[readyIndexes.get(index)] = executed.get(index);
        }

        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        boolean terminate = true;
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
        for (PreparedCall call : prepared) {
            outcomes.add(executePrepared(call));
        }
        return outcomes;
    }

    private List<ToolOutcome> executeParallel(List<PreparedCall> prepared) {
        List<CompletableFuture<ToolOutcome>> futures = new ArrayList<>();
        // 线程池不会继承线程局部变量，在工作线程上显式恢复子代理上下文和取消控制。
        SubagentScope.Context scope = SubagentScope.current();
        for (PreparedCall call : prepared) {
            futures.add(CompletableFuture.supplyAsync(() -> SubagentScope.call(scope, () -> {
                RunControl previous = RunControl.install(control);
                try {
                    return executePrepared(call);
                } finally {
                    RunControl.install(previous);
                }
            })));
        }
        List<ToolOutcome> outcomes = new ArrayList<>();
        for (CompletableFuture<ToolOutcome> future : futures) {
            outcomes.add(future.join());
        }
        return outcomes;
    }

    private ToolOutcome executePrepared(PreparedCall call) {
        ToolOutcome outcome = control.isAborted() ? abortedOutcome(call.toolCall) : runAndFinalize(call);
        emitEnd(outcome);
        return outcome;
    }

    private PreparedCall prepare(AssistantMessage assistantMessage, AssistantMessage.ToolCall toolCall) {
        AgentTool tool = tools.get(toolCall.name());
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
            emitStart(toolCall);
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

    private void emitStart(AssistantMessage.ToolCall call) {
        emit.accept(AgentEvent.builder()
                .type(AgentEventType.TOOL_EXECUTION_START)
                .toolCallId(call.id())
                .toolName(call.name())
                .arguments(call.arguments())
                .build());
    }

    private void emitEnd(ToolOutcome outcome) {
        emit.accept(AgentEvent.builder()
                .type(AgentEventType.TOOL_EXECUTION_END)
                .toolCallId(outcome.response.id())
                .toolName(outcome.response.name())
                .result(outcome.response.responseData())
                .error(outcome.error)
                .durationMs(outcome.durationMs)
                .build());
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

    record ExecutedBatch(List<ToolResponseMessage.ToolResponse> responses, boolean terminate) {
    }
}
