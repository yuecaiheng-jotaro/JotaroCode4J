package org.jotaro.jotarocode.agent.runtime;

import org.reactivestreams.Subscription;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单次运行（以及其派生的并行工具线程、子代理线程）共享的协作式取消控制器。
 *
 * <p>核心职责与取消传播机制：
 * <ol>
 *   <li><b>原子取消标记（{@link #aborted}）</b>：供循环各阶段（Turn 开始前、流式接收中、工具准备前）快速轮询。</li>
 *   <li><b>响应式流取消（{@link #subscription}）</b>：绑定底层 Reactor HTTP 流订阅，{@link #abort()} 时立即调用 {@link Subscription#cancel()} 断开网络流。</li>
 *   <li><b>工作线程中断（{@link #workers}）</b>：记录所有挂载了该控制器的工作线程（包括主循环线程、{@code ToolBatchExecutor} 并行工具线程、{@code SubagentRunner} 子代理线程）。
 *       调用 {@link #abort()} 时会对除调用者自身外的所有工作线程发起 {@link Thread#interrupt()}，
 *       使得阻塞在 {@code Process.waitFor()}、{@code Thread.sleep()} 或 I/O 上的工具无需认识 {@code RunControl} 也能立刻响应中断退出。</li>
 * </ol>
 */
public final class RunControl {

    /** 绑定在当前线程上的活跃 {@link RunControl}，供深层调用链（如子代理工具）通过 {@link #current()} 获取。 */
    private static final ThreadLocal<RunControl> CURRENT = new ThreadLocal<>();

    /** 是否已触发取消。 */
    private final AtomicBoolean aborted = new AtomicBoolean(false);
    /** 当前正在进行的大模型流式响应订阅句柄。 */
    private final AtomicReference<Subscription> subscription = new AtomicReference<>();
    /** 当前登记在此运行控制器名下的所有活跃工作线程集合。 */
    private final Set<Thread> workers = ConcurrentHashMap.newKeySet();

    /**
     * 将当前线程挂载到指定的 {@code control} 上，并返回此前挂载的旧控制器以便在 {@code finally} 中还原。
     *
     * <p>为什么需要”安装 + 还原”模式：
     * <ul>
     *   <li>{@link ThreadLocal} 不会自动跨线程池传递，因此在并行工具线程（{@code ToolBatchExecutor}）和并行子代理线程（{@code SubagentRunner}）启动时，
     *       必须显式调用 {@code install(control)} 将工作线程加入 {@link #workers} 集合，才能在 {@link #abort()} 时收到 {@code interrupt()}。</li>
     *   <li>当子代理与父代理共用线程或线程池复用线程时，任务结束后通过 {@code install(previous)} 恢复现场并从旧 {@code workers} 中移除当前线程，防止线程泄漏或误中断。</li>
     * </ul>
     *
     * @param control 要挂载的控制器（传 {@code null} 表示清理当前线程的挂载）
     * @return 当前线程此前挂载的 {@link RunControl}（可能为 {@code null}）
     */
    public static RunControl install(RunControl control) {
        Thread thread = Thread.currentThread();
        RunControl previous = CURRENT.get();
        if (previous != null && previous != control) {
            previous.workers.remove(thread);
        }
        if (control == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(control);
            control.workers.add(thread);
        }
        return previous;
    }

    /**
     * 获取当前线程挂载的 {@link RunControl}。
     *
     * @return 当前线程关联的控制器，未挂载时返回 {@code null}
     */
    public static RunControl current() {
        return CURRENT.get();
    }

    /**
     * 触发取消：置位 {@code aborted}、取消正在进行的 Reactor 流订阅，并向所有已登记的其他工作线程发送中断信号。
     *
     * <p>注意：故意跳过 {@code worker == caller}（调用 {@code abort()} 的线程自身），避免中断正在执行取消逻辑的调用方线程（例如 HTTP 控制线程或 TUI 信号处理线程）。
     */
    public void abort() {
        aborted.set(true);
        Subscription current = subscription.get();
        if (current != null) {
            current.cancel();
        }
        Thread caller = Thread.currentThread();
        for (Thread worker : workers) {
            if (worker != caller) {
                worker.interrupt();
            }
        }
    }

    /**
     * 绑定底层流式响应的 Reactor {@link Subscription}。
     *
     * <p>竞态条件防护：用户可能在流式请求刚发出、尚未完成 {@code onSubscribe} 回调时就已经按下了 Ctrl+C（此时 {@code aborted} 已为 {@code true}）。
     * 因此在 {@code subscription.set(next)} 之后必须再次检查 {@code aborted.get()}，若已取消则立即补调一次 {@code next.cancel()}。
     *
     * @param next Reactor 流订阅对象
     */
    public void bind(Subscription next) {
        // 取消可能发生在流订阅登记前，登记后再检查一次取消状态。
        subscription.set(next);
        if (aborted.get() && next != null) {
            next.cancel();
        }
    }

    /**
     * 解除当前流订阅绑定（在流结束或终止的 {@code doFinally} 中调用）。
     */
    public void unbind() {
        subscription.set(null);
    }

    /**
     * 判断当前运行是否已被中止。
     *
     * @return {@code true} 表示已触发中止
     */
    public boolean isAborted() {
        return aborted.get();
    }
}
