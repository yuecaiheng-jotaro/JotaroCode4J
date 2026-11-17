package org.jotaro.jotarocode.tui;

import org.jotaro.jotarocode.agent.runtime.AgentLoopRequest;
import org.jotaro.jotarocode.agent.runtime.BeforeToolCallResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * 工具执行前确认问卷的线程同步门控。
 * <p>
 * 负责协调后台 Agent 工具执行线程与主终端读行线程之间的交互：
 * <ul>
 *   <li>当 Agent 准备执行需要确认的复杂工具（见 {@link ToolQuestionnaire#needsAsk(String)}）时，后台线程调用 {@link #beforeCall} 打印问卷并阻塞在 {@link CompletableFuture#get()} 上。</li>
 *   <li>并发调用多个需确认的工具时，通过内部锁 {@link #lock} 串行排队，确保终端一次只向用户提问一个工具。</li>
 *   <li>主读行线程通过 {@link #isWaiting()} 识别出当前正在等待问卷回答，将用户输入交给 {@link #answer(String)} 解析并唤醒等待中的工具线程。</li>
 *   <li>用户输入 {@code a}（全部允许）后，当前会话后续工具调用直接放行；按下 {@code Ctrl+C} 时调用 {@link #cancel()} 以“已中断”状态解除阻塞，防止后台线程永久挂起。</li>
 * </ul>
 */
final class QuestionGate {

    /** 向终端输入框上方输出问卷提示行的打印回调。 */
    private final Consumer<List<String>> printer;
    /** 保护 {@link #allowAll} 与 {@link #pending} 状态流转的同步锁。 */
    private final Object lock = new Object();
    /** 是否已在当前会话中选择“全部允许”（{@code a}），置为 {@code true} 后不再拦截后续工具。 */
    private boolean allowAll;
    /** 当前正在等待用户在终端输入回答的问卷 Future（为空表示当前无挂起问卷）。 */
    private CompletableFuture<BeforeToolCallResult> pending;

    /**
     * 构造问卷同步门控。
     *
     * @param printer 终端多行打印函数
     */
    QuestionGate(Consumer<List<String>> printer) {
        this.printer = printer;
    }

    /**
     * 工具调用前拦截钩子（在 Agent 后台运行线程或工具并发线程上执行）。
     *
     * @param context 工具调用上下文（含工具名与 JSON 参数）
     * @return {@code null} 表示允许执行；非空且 {@code block=true} 表示拦截跳过该工具并把原因返回给模型
     */
    BeforeToolCallResult beforeCall(AgentLoopRequest.BeforeToolCallContext context) {
        String toolName = context.getToolCall().name();
        // 只读工具无需确认，直接放行
        if (!ToolQuestionnaire.needsAsk(toolName)) {
            return null;
        }
        CompletableFuture<BeforeToolCallResult> future = new CompletableFuture<>();
        synchronized (lock) {
            // 若已有其他工具正在等待用户回答，则排队等待前一个问卷完成
            while (pending != null && !allowAll) {
                try {
                    lock.wait();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return blocked("已中断");
                }
            }
            // 等待期间若用户已选择“全部允许”，则直接放行
            if (allowAll) {
                return null;
            }
            pending = future;
        }
        // 在终端输入框上方打印工具确认问卷
        printer.accept(ToolQuestionnaire.prompt(toolName, context.getArguments()));
        try {
            // 运行线程在这里等待，终端读行线程通过 answer 或 cancel 解除等待。
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            future.complete(blocked("已中断"));
            return blocked("已中断");
        } catch (ExecutionException exception) {
            return blocked("问卷没有完成");
        } finally {
            synchronized (lock) {
                if (pending == future) {
                    pending = null;
                }
                // 唤醒下一个排队等待询问的工具线程
                lock.notifyAll();
            }
        }
    }

    /**
     * 由主读行线程提交用户的问卷回答。
     *
     * @param line 用户在终端输入的一行文本
     * @return {@code true} 表示当前确实存在挂起问卷且该行已作为问卷回答消费（不要再发给模型）；{@code false} 表示无挂起问卷
     */
    boolean answer(String line) {
        CompletableFuture<BeforeToolCallResult> future;
        synchronized (lock) {
            future = pending;
        }
        if (future == null) {
            return false;
        }
        ToolQuestionnaire.Parsed parsed = ToolQuestionnaire.parse(line);
        // 空输入时重新提示选项说明，继续保持等待
        if (parsed.kind() == ToolQuestionnaire.Parsed.Kind.AGAIN) {
            printer.accept(List.of("y 允许这次", "a 全部允许", "n 拒绝", "其他文字当作提示"));
            return true;
        }
        // 选择“全部允许”后开启会话级放行开关
        if (parsed.kind() == ToolQuestionnaire.Parsed.Kind.ALLOW_ALL) {
            synchronized (lock) {
                allowAll = true;
            }
            printer.accept(List.of("已全部允许，本次会话不再询问"));
        }
        future.complete(toResult(parsed));
        return true;
    }

    /**
     * 判断当前是否有工具正阻塞等待用户在终端回答问卷。
     *
     * @return 若有挂起问卷则返回 {@code true}
     */
    boolean isWaiting() {
        synchronized (lock) {
            return pending != null;
        }
    }

    /**
     * 取消当前挂起的问卷（如用户按下 {@code Ctrl+C} 或退出时调用），以“已中断”结果唤醒阻塞中的后台线程。
     */
    void cancel() {
        synchronized (lock) {
            if (pending != null) {
                pending.complete(blocked("已中断"));
            }
            lock.notifyAll();
        }
    }

    /**
     * 重置“全部允许”开关（在执行 {@code /reset} 清空会话时调用）。
     */
    void reset() {
        synchronized (lock) {
            allowAll = false;
        }
    }

    /**
     * 将解析后的用户回答映射为工具拦截结果。
     */
    private static BeforeToolCallResult toResult(ToolQuestionnaire.Parsed parsed) {
        return switch (parsed.kind()) {
            case ALLOW_ONCE, ALLOW_ALL -> null;
            case DENY -> blocked("用户拒绝执行这次工具");
            case HINT -> blocked("用户提示，未执行: " + parsed.hint());
            case AGAIN -> null;
        };
    }

    /**
     * 构造拦截工具执行的原因结果对象。
     */
    private static BeforeToolCallResult blocked(String reason) {
        return BeforeToolCallResult.builder().block(true).reason(reason).build();
    }
}
