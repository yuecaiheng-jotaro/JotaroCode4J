package com.kama.piagent.agent.runtime;

import org.reactivestreams.Subscription;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 当前运行的取消开关。abort 会标记取消并取消正在进行的模型流。
 */
public final class RunControl {

    private final AtomicBoolean aborted = new AtomicBoolean(false);
    private final AtomicReference<Subscription> subscription = new AtomicReference<>();

    public void abort() {
        aborted.set(true);
        Subscription current = subscription.get();
        if (current != null) {
            current.cancel();
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
