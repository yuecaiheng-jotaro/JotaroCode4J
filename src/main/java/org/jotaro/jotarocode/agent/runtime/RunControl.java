package org.jotaro.jotarocode.agent.runtime;

import org.reactivestreams.Subscription;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 当前运行的取消开关。abort 会标记取消、取消正在进行的模型流，并打断这次运行登记过的其他工作线程。
 * 阻塞中的工具只要响应中断就能停下来，不必自己认识这个类。
 */
public final class RunControl {

    private static final ThreadLocal<RunControl> CURRENT = new ThreadLocal<>();

    private final AtomicBoolean aborted = new AtomicBoolean(false);
    private final AtomicReference<Subscription> subscription = new AtomicReference<>();
    private final Set<Thread> workers = ConcurrentHashMap.newKeySet();

    /**
     * 把当前线程登记到这次运行上。并行工具在自己的线程里再装一次，因为 ThreadLocal 不会传过去。
     * 返回之前装的开关，离开时再装回去。
     * todo 理解：1. abort 2. 还原
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

    public static RunControl current() {
        return CURRENT.get();
    }

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

    public void bind(Subscription next) {
        subscription.set(next);
        if (aborted.get() && next != null) {
            next.cancel();
        }
    }

    public void unbind() {
        subscription.set(null);
    }

    public boolean isAborted() {
        return aborted.get();
    }
}
