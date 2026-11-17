package org.jotaro.jotarocode.agent.runtime;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 单轮模型响应中批量工具调用（ToolCall Batch）的调度与执行器。
 *
 * <p>核心调度机制与边界处理：
 * <ul>
 *   <li><b>输出截断保护（{@link StopReason#LENGTH}）</b>：
 *       当模型输出因触达最大 Token 限制被截断时，其附带的 ToolCall JSON 参数极可能是半截残缺的。
 *       此时 {@link #failTruncated(List)} 会拒绝执行所有工具，直接返回提示模型重新发起完整调用的错误响应，防止用残缺参数误改文件或执行错误命令。</li>
 *   <li><b>串并行自适应降级</b>：
 *       默认支持并行执行同一轮的多个只读工具（如同时读多个文件或并发搜索）；
 *       但只要全局配置为 {@code SEQUENTIAL}，或者本批工具中有<b>任何一个</b>声明了 {@link ToolExecutionMode#SEQUENTIAL}（如 {@code write}、{@code edit}、{@code bash}），
 *       整批工具都会退化为按顺序串行执行，防止后续工具依赖前一个工具尚未写完的文件状态。</li>
 *   <li><b>结果保序与前置审批（{@code beforeToolCall}）</b>：
 *       无论工具在线程池中谁先跑完，最终拼装回 {@link ToolResponseMessage} 的顺序严格对齐模型原始 {@code toolCalls} 的下标顺序（通过 {@code slots} 数组占位）。
 *       {@link #prepare} 在主线程按顺序依次完成存在性检查与 {@code beforeToolCall} 交互审批，审批通过的工具才送入串行或并行执行阶段。</li>
 *   <li><b>循环终止判定（{@code terminate}）</b>：
 *       正常工具执行后 {@code terminate} 为 {@code false}（驱动 {@link AgentLoop} 将工具结果喂回模型继续下一轮）；
 *       若调用了名为 {@code terminate} 的工具，或所有工具均通过钩子标记了 {@code terminate = true}，则通知循环在本轮结束后停止。</li>
 * </ul>
 */
final class ToolBatchExecutor {

    /** 工具线程池线程命名递增计数器。 */
    private static final AtomicInteger THREAD_ID = new AtomicInteger(1);
    /** 用于并行执行工具的守护线程池（CachedThreadPool 按需复用线程）。 */
    private static final ExecutorService TOOL_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "jotaro-tool-" + THREAD_ID.getAndIncrement());
        thread.setDaemon(true);
        return thread;
    });

    /** 当前循环请求配置（提供钩子与默认执行模式）。 */
    private final AgentLoopRequest request;
    /** 当前运行的取消控制器。 */
    private final RunControl control;
    /** 事件发射器（指向 {@code AgentLoop::emit}）。 */
    private final Consumer<AgentEvent> emit;
    /** 按工具名索引的可用工具映射表。 */
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    /**
     * 构造工具批次执行器，并将可用工具按名称建立索引。
     */
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

    /**
     * 执行单轮助手消息中包含的全部工具调用。
     *
     * @param turn 模型单轮响应
     * @return 包含按序排列的工具响应列表及是否应当终止循环的 {@link ExecutedBatch}
     */
    ExecutedBatch execute(AssistantTurn turn) {
        AssistantMessage message = turn.getMessage();
        List<AssistantMessage.ToolCall> calls = message.getToolCalls();
        // 模型没有发起任何工具调用时，返回空响应列表且 terminate=true（表示自然答复完毕，可以结束循环）
        if (calls == null || calls.isEmpty()) {
            return new ExecutedBatch(List.of(), true);
        }
        // 若模型输出因长度超限被截断，拒绝执行可能残缺的 ToolCall
        return turn.getStopReason() == StopReason.LENGTH
                ? failTruncated(calls)
                : executeToolCalls(message, calls);
    }

    /**
     * 经过前置检查后，按顺序或并行方式执行工具调用，并按原始位置组装结果。
     */
    private ExecutedBatch executeToolCalls(AssistantMessage assistantMessage, List<AssistantMessage.ToolCall> toolCalls) {
        // 默认并行。只要有一个工具声明顺序执行，这一整批都改成顺序，避免后面的调用依赖还没写完的文件。
        boolean sequential = request.getToolExecution() != ToolExecutionMode.PARALLEL || toolCalls.stream().anyMatch(call -> {
            AgentTool tool = tools.get(call.name());
            return tool != null && tool.executionMode() == ToolExecutionMode.SEQUENTIAL;
        });
        // 工具完成事件可以乱序到达，但最终回传给模型的 ToolResponse 列表必须按原始调用下标放入 slots。
        ToolOutcome[] slots = new ToolOutcome[toolCalls.size()];
        List<Integer> readyIndexes = new ArrayList<>();
        List<PreparedCall> ready = new ArrayList<>();

        // 第一阶段：在当前线程按序发送 TOOL_EXECUTION_START 并执行前置检查与 beforeToolCall 审批钩子
        for (int index = 0; index < toolCalls.size(); index++) {
            AssistantMessage.ToolCall toolCall = toolCalls.get(index);
            emitStart(toolCall);
            if (control.isAborted()) {
                slots[index] = abortedOutcome(toolCall);
                emitEnd(slots[index]);
                continue;
            }
            PreparedCall preparation = prepare(assistantMessage, toolCall);
            // 若前置阶段已直接得出结果（如工具不存在、已取消、或被 beforeToolCall 拦截），直接填入对应槽位并发送结束事件
            if (preparation.outcome != null) {
                slots[index] = preparation.outcome;
                emitEnd(slots[index]);
                continue;
            }
            readyIndexes.add(index);
            ready.add(preparation);
        }

        // 第二阶段：对通过前置检查的工具执行实际调用（串行或并行）
        List<ToolOutcome> executed = sequential ? executeSequential(ready) : executeParallel(ready);
        for (int index = 0; index < executed.size(); index++) {
            slots[readyIndexes.get(index)] = executed.get(index);
        }

        // 第三阶段：按原始顺序收集所有 ToolResponse，并计算整批工具是否要求终止循环
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        boolean terminate = true;
        for (ToolOutcome outcome : slots) {
            responses.add(outcome.response);
            terminate = terminate && outcome.terminate;
        }
        // 名字是 terminate 的工具无条件结束循环，即使同批其他工具或钩子没有把 terminate 设为 true。
        if (responses.stream().anyMatch(response -> "terminate".equals(response.name()))) {
            terminate = true;
        }
        return new ExecutedBatch(responses, terminate);
    }

    /**
     * 在当前线程按顺序逐个执行已就绪的工具调用。
     */
    private List<ToolOutcome> executeSequential(List<PreparedCall> prepared) {
        List<ToolOutcome> outcomes = new ArrayList<>();
        for (PreparedCall call : prepared) {
            outcomes.add(executePrepared(call));
        }
        return outcomes;
    }

    /**
     * 使用线程池并行执行已就绪的工具调用，并在每个工作线程中显式桥接 {@link SubagentScope} 和 {@link RunControl}。
     */
    private List<ToolOutcome> executeParallel(List<PreparedCall> prepared) {
        List<CompletableFuture<ToolOutcome>> futures = new ArrayList<>();
        // 线程池不会自动继承 ThreadLocal，先在父线程捕获当前 SubagentScope，再在工作线程中显式挂载它与 RunControl
        SubagentScope.Context scope = SubagentScope.current();
        for (PreparedCall call : prepared) {
            futures.add(CompletableFuture.supplyAsync(() -> SubagentScope.call(scope, () -> {
                RunControl previous = RunControl.install(control);
                try {
                    return executePrepared(call);
                } finally {
                    RunControl.install(previous);
                }
            }), TOOL_EXECUTOR));
        }
        List<ToolOutcome> outcomes = new ArrayList<>();
        for (CompletableFuture<ToolOutcome> future : futures) {
            outcomes.add(future.join());
        }
        return outcomes;
    }

    /**
     * 执行单个已就绪工具并立即广播 {@link AgentEventType#TOOL_EXECUTION_END} 事件。
     */
    private ToolOutcome executePrepared(PreparedCall call) {
        ToolOutcome outcome = control.isAborted() ? abortedOutcome(call.toolCall) : runAndFinalize(call);
        emitEnd(outcome);
        return outcome;
    }

    /**
     * 工具执行前准备：校验工具是否存在、检查取消状态，并执行可选的 {@code beforeToolCall} 钩子。
     *
     * @return 若被拦截或出错则返回带 {@code outcome} 的 {@link PreparedCall#immediate}；否则返回可执行的 {@link PreparedCall#ready}
     */
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

    /**
     * 实际调用 {@link AgentTool#execute(String)}，统计执行耗时，并运行可选的 {@code afterToolCall} 后置钩子。
     */
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

    /**
     * 当模型输出因达到长度上限（{@link StopReason#LENGTH}）被截断时，为每个工具调用生成未执行的错误响应，提示模型重新发起完整调用。
     */
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

    /**
     * 发送单个工具开始执行事件 {@link AgentEventType#TOOL_EXECUTION_START}。
     */
    private void emitStart(AssistantMessage.ToolCall call) {
        emit.accept(AgentEvent.builder()
                .type(AgentEventType.TOOL_EXECUTION_START)
                .toolCallId(call.id())
                .toolName(call.name())
                .arguments(call.arguments())
                .build());
    }

    /**
     * 发送单个工具执行结束事件 {@link AgentEventType#TOOL_EXECUTION_END}。
     */
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

    /**
     * 构造表示失败或被拦截的工具结果。
     */
    private static ToolOutcome errorOutcome(AssistantMessage.ToolCall toolCall, String message, boolean terminate) {
        return new ToolOutcome(response(toolCall, message), true, terminate, null);
    }

    /**
     * 构造表示被用户主动取消的工具结果。
     */
    private static ToolOutcome abortedOutcome(AssistantMessage.ToolCall toolCall) {
        return errorOutcome(toolCall, "操作已取消", false);
    }

    /**
     * 将工具调用描述与结果字符串封装为 Spring AI 的 {@link ToolResponseMessage.ToolResponse}。
     */
    private static ToolResponseMessage.ToolResponse response(AssistantMessage.ToolCall toolCall, String data) {
        return new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), data == null ? "" : data);
    }

    /**
     * 前置准备阶段的结果：若 {@code outcome} 非空则无需真正执行工具，否则持有所需参数进入执行阶段。
     */
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

    /**
     * 单个工具调用的最终产物。
     *
     * @param response   回传给模型的 ToolResponse
     * @param error      是否出错或被拦截
     * @param terminate  是否请求终止循环
     * @param durationMs 实际执行耗时（毫秒，未执行时为 {@code null}）
     */
    private record ToolOutcome(ToolResponseMessage.ToolResponse response, boolean error, boolean terminate, Long durationMs) {
    }

    /**
     * 整批工具调用的汇总结果。
     *
     * @param responses 按原始顺序排列的工具响应列表
     * @param terminate 本批工具执行完后是否应当停止 AgentLoop
     */
    record ExecutedBatch(List<ToolResponseMessage.ToolResponse> responses, boolean terminate) {
    }
}
