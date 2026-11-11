package org.jotaro.jotarocode.agent;

import org.jotaro.jotarocode.agent.runtime.AgentTool;
import org.jotaro.jotarocode.agent.runtime.AssistantTurn;
import org.jotaro.jotarocode.agent.runtime.StopReason;
import org.jotaro.jotarocode.agent.runtime.ToolExecutionMode;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用假的模型流检查：空闲 prompt、运行中 steering、follow-up、中止和重置。 */
class PiAgentTest {

    @Test
    void promptKeepsTheUserMessageAndTheAssistantReply() {
        PiAgent agent = agent((messages, tools, systemPrompt, control, onTextDelta) -> {
            onTextDelta.accept("你好");
            return AssistantTurn.of(new AssistantMessage("你好"), StopReason.STOP);
        });

        agent.prompt("hello");

        List<Message> messages = agent.getMessages();
        assertEquals("hello", ((UserMessage) messages.get(1)).getText());
        assertEquals("你好", messages.get(2).getText());
        assertEquals(AgentState.FINISHED, agent.getState());
    }

    @Test
    void inputDuringARunIsSteeredIntoTheNextTurn() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        PiAgent agent = agent((messages, tools, systemPrompt, control, onTextDelta) -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                started.countDown();
                try {
                    assertTrue(release.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return AssistantTurn.of(new AssistantMessage("first"), StopReason.STOP);
            }
            Message last = messages.get(messages.size() - 1);
            assertEquals("later", ((UserMessage) last).getText());
            return AssistantTurn.of(new AssistantMessage("second"), StopReason.STOP);
        });

        Thread run = new Thread(() -> agent.prompt("now"));
        run.start();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        agent.prompt("later");
        release.countDown();
        run.join(2000);

        assertEquals(2, calls.get());
        assertEquals("second", agent.getMessages().get(agent.getMessages().size() - 1).getText());
    }

    private static PiAgent agent(org.jotaro.jotarocode.agent.runtime.AssistantStreamer streamer) {
        return new PiAgent(
                "system",
                List.<AgentTool>of(),
                streamer,
                8,
                20,
                ToolExecutionMode.SEQUENTIAL
        );
    }
}
