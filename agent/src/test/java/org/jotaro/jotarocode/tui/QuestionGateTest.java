package org.jotaro.jotarocode.tui;

import org.jotaro.jotarocode.agent.runtime.AgentLoopRequest;
import org.jotaro.jotarocode.agent.runtime.BeforeToolCallResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestionGateTest {

    @Test
    void yesAllowsThisCallAndReadDoesNotAsk() throws Exception {
        QuestionGate gate = new QuestionGate(lines -> {
        });
        assertNull(gate.beforeCall(context("read", "{\"path\":\"README.md\"}")));

        Future<BeforeToolCallResult> waiting = ask(gate, "bash", "{\"command\":\"ls\"}");
        waitUntil(gate);
        assertTrue(gate.answer("y"));
        assertNull(waiting.get(2, TimeUnit.SECONDS));
    }

    @Test
    void allowAllSkipsLaterQuestions() throws Exception {
        QuestionGate gate = new QuestionGate(lines -> {
        });
        Future<BeforeToolCallResult> first = ask(gate, "edit", "{\"path\":\"a.java\",\"edits\":[{}]}");
        waitUntil(gate);
        assertTrue(gate.answer("a"));
        assertNull(first.get(2, TimeUnit.SECONDS));
        assertNull(gate.beforeCall(context("bash", "{\"command\":\"ls\"}")));
    }

    @Test
    void hintBlocksExecution() throws Exception {
        QuestionGate gate = new QuestionGate(lines -> {
        });
        Future<BeforeToolCallResult> waiting = ask(gate, "write", "{\"path\":\"a.txt\",\"content\":\"hi\"}");
        waitUntil(gate);
        assertTrue(gate.answer("先写到临时文件"));
        BeforeToolCallResult result = waiting.get(2, TimeUnit.SECONDS);
        assertTrue(result.isBlock());
        assertEquals("用户提示，未执行: 先写到临时文件", result.getReason());
    }

    @Test
    void cancelReleasesTheWaitingCall() throws Exception {
        QuestionGate gate = new QuestionGate(lines -> {
        });
        Future<BeforeToolCallResult> waiting = ask(gate, "bash", "{\"command\":\"sleep 100\"}");
        waitUntil(gate);
        gate.cancel();
        BeforeToolCallResult result = waiting.get(2, TimeUnit.SECONDS);
        assertTrue(result.isBlock());
        assertEquals("已中断", result.getReason());
    }

    private static Future<BeforeToolCallResult> ask(QuestionGate gate, String tool, String arguments) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        return executor.submit(() -> {
            try {
                return gate.beforeCall(context(tool, arguments));
            } finally {
                executor.shutdown();
            }
        });
    }

    private static void waitUntil(QuestionGate gate) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!gate.isWaiting()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("问卷没有开始");
            }
            Thread.sleep(10);
        }
    }

    private static AgentLoopRequest.BeforeToolCallContext context(String name, String arguments) {
        return new AgentLoopRequest.BeforeToolCallContext(
                new AssistantMessage(""),
                new AssistantMessage.ToolCall("1", "function", name, arguments),
                arguments
        );
    }
}
