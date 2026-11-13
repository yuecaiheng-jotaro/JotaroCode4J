package org.jotaro.jotarocode.agent.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RunControlTest {

    @Test
    void abortInterruptsOtherWorkersAndLeavesTheCallerAlone() throws Exception {
        RunControl control = new RunControl();
        CountDownLatch installed = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            RunControl.install(control);
            installed.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException exception) {
                interrupted.countDown();
            } finally {
                RunControl.install(null);
            }
        });
        worker.start();
        assertTrue(installed.await(2, TimeUnit.SECONDS));

        RunControl.install(control);
        try {
            control.abort();
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertTrue(control.isAborted());
            assertTrue(!Thread.currentThread().isInterrupted());
        } finally {
            RunControl.install(null);
            worker.join(2_000);
        }
    }
}
