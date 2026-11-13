package org.jotaro.jotarocode.agent.runtime;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 父代理当前轮带给工具线程的上下文。并行工具跑在别的线程上，ThreadLocal 不会自己过去，
 * 所以 {@link AgentLoop} 在每个工具线程里再装一次。
 */
public final class SubagentScope {

    /**
     * @param model          父会话正在使用的模型名。子代理定义没写模型时用它
     * @param beforeToolCall 父代理的工具确认钩子。子代理改文件或执行命令时沿用同一套确认
     * @param activity       子代理过程。终端用它把子代理嵌套显示出来；没有监听时忽略
     */
    public record Context(
            String model,
            Function<AgentLoopRequest.BeforeToolCallContext, BeforeToolCallResult> beforeToolCall,
            Consumer<SubagentActivity> activity
    ) {
    }

    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private SubagentScope() {
    }

    public static Context current() {
        return CURRENT.get();
    }

    public static void run(Context context, Runnable action) {
        call(context, () -> {
            action.run();
            return null;
        });
    }

    public static <T> T call(Context context, Supplier<T> action) {
        Context previous = CURRENT.get();
        set(context);
        try {
            return action.get();
        } finally {
            set(previous);
        }
    }

    private static void set(Context context) {
        if (context == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(context);
        }
    }
}
