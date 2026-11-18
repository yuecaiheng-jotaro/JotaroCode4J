package org.jotaro.jotarocode.agent;

import org.jotaro.jotarocode.agent.runtime.AgentEvent;
import org.jotaro.jotarocode.agent.runtime.AgentEventType;
import org.jotaro.jotarocode.agent.runtime.AgentLoop;
import org.jotaro.jotarocode.agent.runtime.AgentLoopRequest;
import org.jotaro.jotarocode.agent.runtime.AgentTool;
import org.jotaro.jotarocode.agent.runtime.AssistantStreamer;
import org.jotaro.jotarocode.agent.runtime.BeforeToolCallResult;
import org.jotaro.jotarocode.agent.runtime.ContextCompactor;
import org.jotaro.jotarocode.agent.runtime.PendingMessageQueue;
import org.jotaro.jotarocode.agent.runtime.RunControl;
import org.jotaro.jotarocode.agent.runtime.SubagentActivity;
import org.jotaro.jotarocode.agent.runtime.SubagentScope;
import org.jotaro.jotarocode.agent.runtime.ToolExecutionMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 单个会话的有状态门面（Stateful Session Facade）。
 *
 * <p>架构职责与设计思想要点：
 * <ul>
 *   <li><b>状态与执行分离</b>：{@code JotaroAgent} 自身负责维护跨轮次的完整消息历史 {@link #messages}、运行锁 {@link #runLock}、
 *       待处理消息队列（{@link #steeringQueue} 与 {@link #followUpQueue}）以及会话状态 {@link #state}；
 *       而单次运行的具体 ReAct 循环调度则完全委托给无状态的 {@link AgentLoop}。</li>
 *   <li><b>运行中插话机制（STEER vs FOLLOW_UP）</b>：
 *       当会话处于空闲状态时，调用 {@link #prompt(String)} 或 {@link #followUp(String)} 都会立即开启一轮新的 {@link AgentLoop}；
 *       当会话正在运行中时，再次调用 {@link #prompt(String)} 会将消息放入 {@code steeringQueue}（在当前 Turn 的工具执行完毕后立刻插入对话，
 *       用于中途纠正方向），而调用 {@link #followUp(String)} 会将消息放入 {@code followUpQueue}（等到本轮所有工具调用结束、循环即将退出前才插入，
 *       用于追加后续任务）。</li>
 *   <li><b>快照隔离与增量写回</b>：启动 {@link AgentLoop} 时传入的是当前 {@link #messages} 的浅拷贝快照，
 *       循环内部的临时裁剪不会直接污染外部列表；当循环内部每完成一条完整消息并抛出 {@code MESSAGE_END} 事件时，
 *       {@link #dispatch(AgentEvent)} 再加锁将该消息追加回 {@link #messages}。</li>
 * </ul>
 */
@Slf4j
public class JotaroAgent {

    /**
     * 会话级系统提示词（包含基础角色设定、技能列表提示、MCP 提示及知识库提示）。
     * 在构造和 {@link #reset()} 时作为首条 {@link SystemMessage} 放入消息历史。
     */
    private final String systemPrompt;

    /**
     * 当前会话可用的工具集合（只读不可变列表）。
     */
    private final List<AgentTool> tools;

    /**
     * 单次运行允许的最大模型调用步数（Turn 数量上限），防止模型陷入无限工具调用死循环。
     */
    private final int maxSteps;

    /**
     * 每次发给大模型的滑动消息窗口大小（条数限制），由 {@link AgentLoop} 在请求模型前裁剪。
     */
    private final int messageWindow;

    /**
     * 同一轮中含有多个工具调用时的默认执行模式（顺序或并行）。
     */
    private final ToolExecutionMode toolExecution;

    /**
     * 上下文压缩器（负责启动压缩子代理按比例浓缩历史消息，默认压缩前 70%）。
     */
    private final ContextCompactor compactor;

    /**
     * 完整的会话消息历史（包含 SystemMessage、UserMessage、AssistantMessage、ToolResponseMessage）。
     * 所有读写均受 {@link #runLock} 保护。
     */
    private final List<Message> messages = new ArrayList<>();

    /**
     * Steering（中途引导/插话）消息队列。
     * 当 Agent 正在运行时调用 {@link #prompt(String)}，消息会进入此队列，并在当前轮次工具执行完毕后立即被 {@link AgentLoop} 取出消费。
     */
    private final PendingMessageQueue steeringQueue = new PendingMessageQueue();

    /**
     * Follow-up（后续追问）消息队列。
     * 当 Agent 正在运行时调用 {@link #followUp(String)}，消息会进入此队列，仅在当前循环没有工具可调且 {@code steeringQueue} 为空、即将结束时才被取出消费。
     */
    private final PendingMessageQueue followUpQueue = new PendingMessageQueue();

    /**
     * 事件订阅者列表。使用 {@link CopyOnWriteArrayList} 保证在运行期间动态注册监听器时的线程安全。
     */
    private final List<Consumer<AgentEvent>> listeners = new CopyOnWriteArrayList<>();

    /**
     * 会话级同步锁，保护 {@link #running}、{@link #messages}、{@link #model}、{@link #streamer} 和 {@link #runControl} 的并发访问与状态切换。
     */
    private final Object runLock = new Object();

    /**
     * 底层大模型流式调用器，切换模型时会随之一并替换。
     */
    private AssistantStreamer streamer;

    /**
     * 当前会话使用的模型名称。当派生子代理且子代理定义未显式指定模型时，通过 {@link SubagentScope} 继承该模型名。
     */
    private volatile String model;

    /**
     * 父子共享的取消控制器。
     * 普通顶层会话为 {@code null}（每次运行新建独立的 {@link RunControl}）；
     * 子代理构造时传入父代理的 {@link RunControl}，使得用户在父会话按 Ctrl+C / abort 时能一并中断子代理的流式输出与工具线程。
     */
    private final RunControl sharedControl;

    /**
     * 当前正在执行的轮次所持有的取消开关，每次开启新运行时在锁内刷新。
     */
    private volatile RunControl runControl = new RunControl();

    /**
     * 标记当前是否有 {@link AgentLoop} 正在执行。
     */
    private volatile boolean running;

    /**
     * 当前会话对外可见的状态机状态。
     */
    private volatile AgentState state = AgentState.IDLE;

    /**
     * 工具调用前的拦截/确认钩子（如 TUI 下的高危工具交互确认）。会通过 {@link SubagentScope} 传递给子代理复用。
     */
    private volatile Function<AgentLoopRequest.BeforeToolCallContext, BeforeToolCallResult> beforeToolCall;

    /**
     * 子代理执行进度回调，供 TUI 或前端实时展示子代理内部的工具调用与流式输出。
     */
    private volatile Consumer<SubagentActivity> subagentActivity;

    /**
     * 创建顶层会话 Agent（不共享外部 {@link RunControl}）。
     *
     * @param systemPrompt  系统提示词
     * @param tools         可用工具列表
     * @param streamer      模型流式调用器
     * @param maxSteps      最大循环步数
     * @param messageWindow 发给模型的消息窗口大小
     * @param toolExecution 工具默认执行模式（为空时默认并行）
     */
    public JotaroAgent(String systemPrompt,
                   List<AgentTool> tools,
                   AssistantStreamer streamer,
                   int maxSteps,
                   int messageWindow,
                   ToolExecutionMode toolExecution) {
        this(systemPrompt, tools, streamer, maxSteps, messageWindow, toolExecution, null);
    }

    /**
     * 创建会话 Agent，支持注入父代理的 {@link RunControl}（供子代理场景共享取消信号）。
     *
     * @param systemPrompt  系统提示词
     * @param tools         可用工具列表
     * @param streamer      模型流式调用器
     * @param maxSteps      最大循环步数
     * @param messageWindow 发给模型的消息窗口大小
     * @param toolExecution 工具默认执行模式（为空时默认并行）
     * @param sharedControl 共享的取消开关（顶层 Agent 传 {@code null}，子代理传父代理的 {@code RunControl}）
     */
    public JotaroAgent(String systemPrompt,
                   List<AgentTool> tools,
                   AssistantStreamer streamer,
                   int maxSteps,
                   int messageWindow,
                   ToolExecutionMode toolExecution,
                   RunControl sharedControl) {
        this(systemPrompt, tools, streamer, maxSteps, messageWindow, toolExecution, sharedControl, new ContextCompactor());
    }

    /**
     * 创建会话 Agent，支持注入自定义 {@link ContextCompactor}（为 {@code null} 时不启用子代理压缩）。
     *
     * @param systemPrompt  系统提示词
     * @param tools         可用工具列表
     * @param streamer      模型流式调用器
     * @param maxSteps      最大循环步数
     * @param messageWindow 发给模型的消息窗口大小
     * @param toolExecution 工具默认执行模式（为空时默认并行）
     * @param sharedControl 共享的取消开关（顶层 Agent 传 {@code null}，子代理传父代理的 {@code RunControl}）
     * @param compactor     上下文压缩器
     */
    public JotaroAgent(String systemPrompt,
                   List<AgentTool> tools,
                   AssistantStreamer streamer,
                   int maxSteps,
                   int messageWindow,
                   ToolExecutionMode toolExecution,
                   RunControl sharedControl,
                   ContextCompactor compactor) {
        this.systemPrompt = systemPrompt;
        this.tools = List.copyOf(tools == null ? List.of() : tools);
        this.streamer = streamer;
        this.maxSteps = maxSteps;
        this.messageWindow = messageWindow;
        this.toolExecution = toolExecution == null ? ToolExecutionMode.PARALLEL : toolExecution;
        this.sharedControl = sharedControl;
        this.compactor = compactor;
        if (StringUtils.hasText(systemPrompt)) {
            messages.add(new SystemMessage(systemPrompt));
        }
    }

    /**
     * 注册会话事件监听器（如 SSE 推送或 TUI 渲染器）。
     *
     * @param listener 事件消费回调
     */
    public void subscribe(Consumer<AgentEvent> listener) {
        listeners.add(listener);
    }

    /**
     * 切换当前会话所使用的模型及其流式调用器。
     *
     * <p>边界条件：必须在非运行状态下切换，否则正在进行的流式请求和新增轮次会出现模型不一致。
     *
     * @param model    目标模型名称
     * @param streamer 对应模型的流式调用实现
     * @throws IllegalStateException 如果当前 Agent 正在运行中
     */
    public void useModel(String model, AssistantStreamer streamer) {
        synchronized (runLock) {
            if (running) {
                throw new IllegalStateException("Agent 正在运行，不能切换模型");
            }
            this.model = model;
            this.streamer = streamer;
        }
    }

    /**
     * 设置工具调用前钩子（例如 TUI 下的权限审批弹窗）。
     *
     * @param beforeToolCall 工具调用前回调函数
     */
    public void setBeforeToolCall(Function<AgentLoopRequest.BeforeToolCallContext, BeforeToolCallResult> beforeToolCall) {
        this.beforeToolCall = beforeToolCall;
    }

    /**
     * 设置子代理活动事件监听器，用于实时观测子代理内部执行动态。
     *
     * @param subagentActivity 子代理活动消费者
     */
    public void setSubagentActivity(Consumer<SubagentActivity> subagentActivity) {
        this.subagentActivity = subagentActivity;
    }

    /**
     * 判断当前是否正在执行对话循环。
     *
     * @return {@code true} 表示正在运行中
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * 获取当前会话的状态枚举。
     *
     * @return 当前 {@link AgentState}
     */
    public AgentState getState() {
        return state;
    }

    /**
     * 获取当前完整会话消息历史的不可变副本。
     *
     * @return 消息历史只读列表
     */
    public List<Message> getMessages() {
        synchronized (runLock) {
            return List.copyOf(messages);
        }
    }

    /**
     * 发送纯文本用户输入。
     * 空闲时立即开启新一轮 {@link AgentLoop}；若当前正在运行，则将该消息放入 {@link #steeringQueue} 排队，在当前轮工具执行完后插话。
     *
     * @param text 用户输入文本
     */
    public void prompt(String text) {
        prompt(text, List.of());
    }

    /**
     * 发送带多模态媒体附件（如剪贴板图片）的用户输入。
     * 纯文本模型会忽略图片附件，多模态模型可识别图片内容。
     *
     * @param text  用户输入文本
     * @param media 媒体附件列表
     */
    public void prompt(String text, List<Media> media) {
        if (!beginRun(userMessage(text, media), false)) {
            return;
        }
        executeLoop();
    }

    /**
     * 发送后续追问（Follow-up）消息。
     * 空闲时行为等同于 {@link #prompt(String)}；若当前正在运行，则放入 {@link #followUpQueue}，等到本轮循环即将自然结束前再插入继续执行。
     *
     * @param text 追问文本
     */
    public void followUp(String text) {
        if (!beginRun(text, true)) {
            return;
        }
        executeLoop();
    }

    /**
     * 尝试开启一次新的运行，若已在运行中则将文本转为消息入队。
     *
     * @param text     用户输入文本
     * @param followUp {@code true} 表示入 {@link #followUpQueue}，{@code false} 表示入 {@link #steeringQueue}
     * @return {@code true} 表示成功抢占运行权并需要启动 {@link #executeLoop()}；{@code false} 表示仅入队等待正在运行的循环消费
     */
    private boolean beginRun(String text, boolean followUp) {
        return beginRun(userMessage(text, List.of()), followUp);
    }

    /**
     * 尝试开启一次新的运行的核心加锁方法。
     *
     * <p>并发边界设计：新的 {@link RunControl} 必须在释放 {@link #runLock} 之前就赋值完成。
     * 否则如果放到 {@link #runLoop()} 里才初始化，调用方刚触发 {@code prompt} 就立刻按 Ctrl+C 调用 {@link #abort()}，
     * 取消信号可能打在上一轮遗留的旧 {@link RunControl} 上，导致新一轮无法感知取消。
     *
     * @param message  封装好的用户消息
     * @param followUp 是否作为 followUp 消息入队
     * @return {@code true} 表示当前由空闲转入运行态；{@code false} 表示已在运行中、消息仅追加进等待队列
     */
    private boolean beginRun(UserMessage message, boolean followUp) {
        synchronized (runLock) {
            if (running) {
                (followUp ? followUpQueue : steeringQueue).enqueue(message);
                return false;
            }
            state = AgentState.THINKING;
            messages.add(message);
            runControl = sharedControl != null ? sharedControl : new RunControl();
            running = true;
            return true;
        }
    }

    /**
     * 中止当前正在进行的运行（取消模型流式订阅并中断关联的工具执行线程）。
     */
    public void abort() {
        runControl.abort();
    }

    /**
     * 重置当前会话：清空对话历史（仅保留初始 {@link SystemMessage}）、清空排队队列并将状态恢复为 {@link AgentState#IDLE}。
     *
     * @throws IllegalStateException 如果当前正在运行中
     */
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

    /**
     * 使用默认配置的压缩比例（默认 70%）手动触发子代理上下文压缩。
     */
    public ContextCompactor.Result compact() {
        return compact(null);
    }

    /**
     * 手动启动压缩子代理，将当前会话前 {@code customRatio}（为空时使用配置的默认比例 70%）的历史对话压缩为摘要。
     *
     * @param customRatio 自定义压缩比例（支持 0.1~0.95 或 10~95；为 {@code null} 时使用默认配置比例）
     * @return 压缩统计与结果
     * @throws IllegalStateException 如果当前 Agent 正在运行中
     */
    public ContextCompactor.Result compact(Double customRatio) {
        List<Message> snapshot;
        AssistantStreamer currentStreamer;
        RunControl control;
        synchronized (runLock) {
            if (running) {
                throw new IllegalStateException("Agent 正在运行，不能执行上下文压缩");
            }
            snapshot = new ArrayList<>(messages);
            currentStreamer = this.streamer;
            control = new RunControl();
            this.runControl = control;
            this.running = true;
            this.state = AgentState.THINKING;
        }
        ContextCompactor activeCompactor = this.compactor != null ? this.compactor : new ContextCompactor();
        double targetRatio = customRatio == null ? activeCompactor.ratio() : ContextCompactor.normalizeRatio(customRatio);
        try {
            return SubagentScope.call(new SubagentScope.Context(model, beforeToolCall, subagentActivity), () -> {
                ContextCompactor.Result result = activeCompactor.compact(snapshot, currentStreamer, control, targetRatio);
                if (result.compacted()) {
                    synchronized (runLock) {
                        messages.clear();
                        messages.addAll(result.messages());
                    }
                }
                return result;
            });
        } finally {
            Thread.interrupted();
            synchronized (runLock) {
                running = false;
                if (state != AgentState.ERROR) {
                    state = AgentState.FINISHED;
                }
            }
        }
    }

    /**
     * 在绑定了 {@link SubagentScope} 上下文（包含当前模型名、工具前置确认钩子、子代理活动回调）的作用域内执行主循环。
     * 这样当循环内部调用 {@code subagent} 工具时，子代理能够通过线程局部变量获取到父代理的上下文。
     */
    private void executeLoop() {
        SubagentScope.run(new SubagentScope.Context(model, beforeToolCall, subagentActivity), this::runLoop);
    }

    /**
     * 构造 {@link AgentLoopRequest} 并驱动 {@link AgentLoop#run()} 同步运行。
     */
    private void runLoop() {
        RunControl control = this.runControl;
        try {
            List<Message> snapshot;
            synchronized (runLock) {
                // 拷贝当前消息列表作为本轮循环的初始快照
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
                    .model(model)
                    .compactor(compactor)
                    .onMessagesCompacted(compacted -> {
                        synchronized (runLock) {
                            messages.clear();
                            messages.addAll(compacted);
                        }
                    })
                    .build(), this::dispatch).run();
        } catch (RuntimeException e) {
            state = AgentState.ERROR;
            log.error("Agent 运行失败", e);
            throw e;
        } finally {
            // 关键清理：abort() 会对当前工作线程调用 interrupt()。
            // 如果不清除线程中断标记，当该线程（例如线程池复用或 TUI 主循环线程）执行下一次任务时，会被残留的中断标记立即打断。
            Thread.interrupted();
            synchronized (runLock) {
                running = false;
                if (state != AgentState.ERROR) {
                    state = AgentState.FINISHED;
                }
            }
        }
    }

    /**
     * 接收 {@link AgentLoop} 抛出的运行时事件：将新生成的完整消息同步回会话历史，更新会话状态机，并广播给所有外部订阅者。
     *
     * @param event 循环内部发出的事件
     */
    private void dispatch(AgentEvent event) {
        // 每当一轮中的消息（中途插入的用户消息、助手回复消息、工具结果消息）完成时，追加回主会话历史
        if (event.getType() == AgentEventType.MESSAGE_END && event.getMessage() != null) {
            synchronized (runLock) {
                messages.add(event.getMessage());
            }
        }
        // 根据事件类型驱动会话状态机转换
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

        // 事件广播：依次触发所有监听当前 Agent 事件的订阅者（如 SSE 推送器、TUI 渲染器、子代理监听器）
        listeners.forEach(listener -> listener.accept(event));

    }

    /**
     * 将文本和可选的媒体列表组装为 Spring AI 的 {@link UserMessage}。
     *
     * @param text  用户文本（若为 {@code null} 则转为空串）
     * @param media 媒体附件列表
     * @return 构建好的 {@link UserMessage}
     */
    private static UserMessage userMessage(String text, List<Media> media) {
        String body = text == null ? "" : text;
        if (media == null || media.isEmpty()) {
            return new UserMessage(body);
        }
        return UserMessage.builder().text(body).media(media).build();
    }
}
