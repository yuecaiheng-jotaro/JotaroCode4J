package org.jotaro.jotarocode.agent.runtime;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 父代理向工具执行线程及子代理（Subagent）跨层传递运行时上下文的线程局部作用域（ThreadLocal Scope）。
 *
 * <p>为什么需要 {@code SubagentScope} 以及如何跨线程传递：
 * <ul>
 *   <li>{@code subagent} 工具本质上是一个普通的 {@link AgentTool}，其方法签名只有 {@code execute(String arguments)}，
 *       无法通过方法参数直接拿到父会话当前使用的模型名、工具确认钩子 {@code beforeToolCall} 和 UI 活动监听器 {@code activity}。</li>
 *   <li>因此父 {@code JotaroAgent} 在启动主循环前通过 {@link #run(Context, Runnable)} 将上下文绑定到当前线程的 {@link ThreadLocal}。</li>
 *   <li>当 {@link ToolBatchExecutor} 使用线程池并行执行工具，或 {@code SubagentRunner} 使用线程池并行派生多个子代理时，
 *       因为普通 {@link ThreadLocal} 不会自动跨线程传播，调度器会先在父线程调用 {@link #current()} 捕获引用，
 *       再在工作线程内通过 {@link #call(Context, Supplier)} 显式挂载并在 {@code finally} 中恢复，从而实现零侵入的跨线程上下文穿透。</li>
 * </ul>
 */
public final class SubagentScope {

    /**
     * 父会话传递给子代理的上下文数据。
     *
     * @param model          父会话当前正在使用的模型名；当子代理定义未指定专用模型时自动继承该模型
     * @param beforeToolCall 父会话的工具调用前置确认钩子；子代理执行写文件或运行命令时沿用同一套审批逻辑
     * @param activity       子代理执行过程回调；TUI 终端用它将子代理的内部步骤实时嵌套渲染出来，未监听时可为 {@code null}
     */
    public record Context(
            String model,
            Function<AgentLoopRequest.BeforeToolCallContext, BeforeToolCallResult> beforeToolCall,
            Consumer<SubagentActivity> activity
    ) {
    }

    /** 保存当前线程关联的子代理上下文。 */
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private SubagentScope() {
    }

    /**
     * 获取当前线程绑定的 {@link Context}。
     *
     * @return 当前上下文，未绑定时返回 {@code null}
     */
    public static Context current() {
        return CURRENT.get();
    }

    /**
     * 在给定的 {@link Context} 作用域内执行无返回值动作，执行完毕后自动恢复原上下文。
     *
     * @param context 要绑定的上下文
     * @param action  待执行的动作
     */
    public static void run(Context context, Runnable action) {
        call(context, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 在给定的 {@link Context} 作用域内执行带返回值的动作，并在 {@code finally} 块中将线程上下文还原为进入前的状态。
     *
     * @param context 要绑定的上下文
     * @param action  待执行的带返回值动作
     * @param <T>     返回值类型
     * @return {@code action} 的执行结果
     */
    public static <T> T call(Context context, Supplier<T> action) {
        Context previous = CURRENT.get();
        set(context);
        try {
            return action.get();
        } finally {
            set(previous);
        }
    }

    /**
     * 设置或清除当前线程的 {@link Context}，避免传入 {@code null} 时残留在 {@link ThreadLocal} Map 中。
     */
    private static void set(Context context) {
        if (context == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(context);
        }
    }
}
