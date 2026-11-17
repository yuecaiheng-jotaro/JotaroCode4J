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
 * 构建在 Spring AI 之上的核心 ReAct 智能体循环引擎。
 *
 * <p>核心运行机制与设计要点：
 * <ul>
 *   <li><b>每一轮（Turn）的定义</b>：一次大模型流式调用（产出 {@link AssistantMessage}）加上该消息所要求的所有工具调用（产出 {@link ToolResponseMessage}）。</li>
 *   <li><b>两级插话队列（STEER vs FOLLOW_UP）的消费时机</b>：
 *       <ul>
 *         <li>{@code steeringMessages}：在循环启动前以及每一轮工具执行完成后立即尝试取出。只要非空，就会在下一轮开头追加进对话并继续请求模型，让用户能在工具执行间隙及时纠正 Agent 方向。</li>
 *         <li>{@code followUpMessages}：仅当本轮准备终止（没有工具调用或调用了 {@code terminate} 工具）且 {@code steeringMessages} 为空时才尝试取出。若非空，则阻止循环退出，开启新一轮处理后续追问。</li>
 *       </ul>
 *   </li>
 *   <li><b>滑动消息窗口与 ToolCall/ToolResponse 配对保护</b>：
 *       {@link #visibleMessages()} 在向模型发送上下文前按 {@code modelWindow} 裁剪历史消息。
 *       裁剪时始终保留首条 {@link SystemMessage}，并且如果窗口起点恰好落在 {@link ToolResponseMessage} 上，
 *       会自动向前扩展起点，防止把 {@link ToolResponseMessage} 和它前面配对的含 {@code toolCalls} 的 {@link AssistantMessage} 拆散（否则大模型 API 会报消息协议校验错误）。</li>
 * </ul>
 */
@Slf4j
public final class AgentLoop {

    /** 本次循环的不可变请求配置与钩子集合。 */
    private final AgentLoopRequest request;
    /** 事件监听回调（由外部如 {@code JotaroAgent::dispatch} 传入）。 */
    private final Consumer<AgentEvent> listener;
    /** 循环内部维护的工作消息列表（基于初始快照演进）。 */
    private final List<Message> messages;
    /** 当前运行的取消控制器。 */
    private final RunControl control;
    /** 负责单轮内批量工具调用（顺序/并行）的执行器。 */
    private final ToolBatchExecutor toolExecutor;

    /**
     * 构造一次智能体循环。
     *
     * @param request  循环输入参数与钩子配置
     * @param listener 运行时事件接收器
     */
    public AgentLoop(AgentLoopRequest request, Consumer<AgentEvent> listener) {
        this.request = request;
        this.listener = listener;
        this.messages = new ArrayList<>(request.getMessages() == null ? List.of() : request.getMessages());
        this.control = request.getControl() == null ? new RunControl() : request.getControl();
        this.toolExecutor = new ToolBatchExecutor(request, control, this::emit);
    }

    /**
     * 启动循环并在当前线程安装 {@link RunControl}，确保循环及同线程工具能感知取消信号；退出时恢复原线程的控制器。
     */
    public void run() {
        RunControl previous = RunControl.install(control);
        try {
            doRun();
        } finally {
            RunControl.install(previous);
        }
    }

    /**
     * 执行 ReAct 主循环。
     */
    private void doRun() {
        // 启动前先拉取一次可能已排队的 steering 消息
        List<Message> pending = drain(request.getSteeringMessages());
        emit(AgentEvent.of(AgentEventType.AGENT_START));

        for (int steps = 1; ; steps++) {
            emit(AgentEvent.of(AgentEventType.TURN_START));
            // 将上一轮末尾取出的 steering 或 follow-up 消息正式加入对话并广播 MESSAGE_START / MESSAGE_END
            appendPending(pending);
            if (control.isAborted()) {
                finish(false);
                return;
            }

            // 1. 流式请求大模型
            AssistantTurn turn = streamAssistant();
            boolean failed = turn.getStopReason() == StopReason.ERROR || turn.getStopReason() == StopReason.ABORTED;
            messages.add(turn.getMessage());
            emit(AgentEvent.builder()
                    .type(AgentEventType.MESSAGE_END)
                    .message(turn.getMessage())
                    .error(failed)
                    .build());

            // 2. 若模型调用未失败，执行模型返回的全部 ToolCall（无工具调用时返回空列表且 terminate=true）
            ToolBatchExecutor.ExecutedBatch batch = failed
                    ? new ToolBatchExecutor.ExecutedBatch(List.of(), true)
                    : toolExecutor.execute(turn);
            appendToolResults(batch.responses());

            // 3. 调用可选的 finishTurn 钩子并发送 TURN_END 事件
            TurnDecision decision = invokeFinishTurn(turn);
            emitTurnEnd(turn, batch.responses());
            if (failed || decision == TurnDecision.END || control.isAborted()) {
                finish(false);
                return;
            }
            // 4. 检查是否达到最大步数限制
            if (request.getMaxSteps() > 0 && steps >= request.getMaxSteps()) {
                log.warn("达到最大步数 {}，停止 Agent", request.getMaxSteps());
                finish(true);
                return;
            }

            // 5. 优先消费 steering 队列：只要本轮执行了普通工具（!batch.terminate()）或者有新的 steering 消息插进来，就立刻进入下一轮
            pending = drain(request.getSteeringMessages());
            if (!batch.terminate() || !pending.isEmpty()) {
                continue;
            }
            // 6. 本轮已无工具可调且无 steering 消息、即将停止时，再检查 follow-up 队列和 CONTINUE 决策
            pending = drain(request.getFollowUpMessages());
            if (pending.isEmpty() && decision != TurnDecision.CONTINUE) {
                finish(false);
                return;
            }
        }
    }

    /**
     * 将本轮所有工具调用的执行结果打包为一条 {@link ToolResponseMessage} 追加进历史并通知外部订阅者。
     *
     * @param results 工具响应列表（为空时不产生消息）
     */
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

    /**
     * 发送 {@link AgentEventType#AGENT_END} 事件标志整次循环结束。
     *
     * @param stepLimitReached 是否因触达最大步数限制而结束
     */
    private void finish(boolean stepLimitReached) {
        emit(AgentEvent.builder()
                .type(AgentEventType.AGENT_END)
                .stepLimitReached(stepLimitReached)
                .build());
    }

    /**
     * 发送流式文本或思考内容增量事件。
     *
     * @param delta     新增文本片段
     * @param reasoning {@code true} 表示思考过程增量，{@code false} 表示正式回复增量
     */
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

    /**
     * 调用底层 {@link AssistantStreamer} 获取一轮模型响应，并将异常与取消统一收敛为 {@link AssistantTurn}。
     *
     * @return 模型单轮响应结果（永不为 {@code null}）
     */
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
     * 计算发给大模型的滑动窗口消息列表。
     *
     * <p>边界条件处理：
     * <ol>
     *   <li>若 {@code modelWindow <= 0} 或当前消息总数未超过窗口大小，直接返回全量消息副本。</li>
     *   <li>为什么不能在 {@link ToolResponseMessage} 处截断：OpenAI / DeepSeek / Qwen 等聊天协议严格要求
     *       {@code role=tool} 的消息前面必须紧跟发起该调用的 {@code role=assistant (tool_calls)} 消息。
     *       如果滑动窗口起点 {@code start} 恰好落在 {@code ToolResponseMessage} 上，直接截断会导致孤立的 ToolResponse 报错，
     *       因此这里将 {@code start} 向前退到非 {@code ToolResponseMessage}（即对应的 {@code AssistantMessage}）为止。</li>
     *   <li>若裁剪后丢弃了索引 0，而索引 0 是 {@link SystemMessage}，则将其单独补回窗口首位，保证系统提示词永不丢失。</li>
     * </ol>
     * todo 做上下文压缩
     *
     * @return 裁剪后发给模型的消息列表
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

    /**
     * 将排队取出的消息（steering 或 follow-up）追加进当前上下文，并完整发送 {@code MESSAGE_START} 和 {@code MESSAGE_END} 事件，
     * 使得外部 {@code JotaroAgent} 能将其持久化到主消息列表中。
     *
     * @param pending 待插入的消息列表
     */
    private void appendPending(List<Message> pending) {
        for (Message message : pending) {
            messages.add(message);
            emit(AgentEvent.builder().type(AgentEventType.MESSAGE_START).message(message).build());
            emit(AgentEvent.builder().type(AgentEventType.MESSAGE_END).message(message).build());
        }
    }

    /**
     * 发送单轮结束事件 {@link AgentEventType#TURN_END}。
     */
    private void emitTurnEnd(AssistantTurn turn, List<ToolResponseMessage.ToolResponse> toolResults) {
        emit(AgentEvent.builder()
                .type(AgentEventType.TURN_END)
                .message(turn.getMessage())
                .toolResults(toolResults)
                .error(turn.getStopReason() == StopReason.ERROR || turn.getStopReason() == StopReason.ABORTED)
                .build());
    }

    /**
     * 调用可选的 {@code finishTurn} 钩子，允许调用方根据本轮输出自定义决定是否强制结束或继续。
     * 当本轮已发生错误或取消时，忽略钩子返回的继续指令，确保能够及时停机。
     */
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

    /**
     * 线程安全地向监听器发送事件。
     * 由于并行工具执行期间多个工作线程可能同时调用 {@code emit}，这里对 {@code listener} 加锁以保证事件串行有序送达。
     */
    private void emit(AgentEvent event) {
        synchronized (listener) {
            listener.accept(event);
        }
    }

    /**
     * 从消息队列提供者中拉取待处理消息，做空指针防护并返回不可变副本。
     */
    private static List<Message> drain(java.util.function.Supplier<List<Message>> supplier) {
        if (supplier == null) {
            return List.of();
        }
        List<Message> drained = supplier.get();
        return drained == null ? List.of() : List.copyOf(drained);
    }

}
