package org.jotaro.jotarocode.agent.runtime;

import org.jotaro.jotarocode.agent.JotaroAgent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextCompactorTest {

    @Test
    void normalizesDecimalAndPercentageRatiosWith70PercentDefault() {
        assertEquals(0.7, ContextCompactor.normalizeRatio(null), 1e-6);
        assertEquals(0.7, ContextCompactor.normalizeRatio(0.7), 1e-6);
        assertEquals(0.7, ContextCompactor.normalizeRatio(70.0), 1e-6);
        assertEquals(0.8, ContextCompactor.parseRatio("80%", 0.7), 1e-6);
        assertEquals(0.5, ContextCompactor.parseRatio("0.5", 0.7), 1e-6);
        assertEquals(0.0, ContextCompactor.normalizeRatio(0.0), 1e-6);
    }

    @Test
    void triggersWhenContextExceeds80PercentOfSelectedModelContextLimit() {
        ContextCompactor compactor = new ContextCompactor(
                0.7,
                0.8,
                1000,
                Map.of("small-model", 100, "large-model", 10000)
        );
        List<Message> messages = List.of(
                new SystemMessage("system"),
                new UserMessage("这是一段用于测试上下文水位阈值的历史用户消息，长度超过八十个Token阈值"),
                new AssistantMessage("这是一段用于测试上下文水位阈值的历史助手消息，长度同样超过八十个Token阈值")
        );
        int estimated = ContextCompactor.estimateTokens(messages);
        assertTrue(estimated >= 80, "estimated tokens: " + estimated);

        // 对上下文上限为 100 的 small-model（阈值 100 * 0.8 = 80），达到阈值触发压缩
        assertTrue(compactor.shouldCompact(messages, "small-model"));
        // 对上下文上限为 10000 的 large-model（阈值 10000 * 0.8 = 8000），未达到阈值不触发
        assertFalse(compactor.shouldCompact(messages, "large-model"));
    }

    @Test
    void compactsOldest70PercentViaSubagentAndKeepsRecent30Percent() {
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage("system-prompt"));
        for (int i = 1; i <= 5; i++) {
            history.add(new UserMessage("user-" + i));
            history.add(new AssistantMessage("assistant-" + i));
        }
        // 共 10 条对话消息，70% 即前 7 条（user-1..user-4），保留后 3 条（assistant-4, user-5, assistant-5）
        AtomicInteger subagentCalls = new AtomicInteger();
        AssistantStreamer streamer = (messages, tools, systemPrompt, control, onTextDelta) -> {
            subagentCalls.incrementAndGet();
            assertTrue(tools == null || tools.isEmpty());
            return AssistantTurn.of(new AssistantMessage("已完成前序步骤1至4的总结"), StopReason.STOP);
        };

        ContextCompactor compactor = new ContextCompactor(0.7);
        ContextCompactor.Result result = compactor.compact(history, streamer, new RunControl());

        assertTrue(result.compacted());
        assertEquals(1, subagentCalls.get());
        assertEquals(7, result.compactedCount());
        assertEquals(11, result.beforeTotal());

        List<Message> compacted = result.messages();
        assertInstanceOf(SystemMessage.class, compacted.get(0));
        assertTrue(compacted.get(1).getText().contains("已完成前序步骤1至4的总结"));
        assertEquals("assistant-4", compacted.get(2).getText());
        assertEquals("user-5", compacted.get(3).getText());
        assertEquals("assistant-5", compacted.get(4).getText());
    }

    @Test
    void doesNotSplitToolCallAndToolResponsePairAtSplitBoundary() {
        AssistantMessage toolCallMsg = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "read", "{\"path\":\"a.txt\"}")))
                .build();
        ToolResponseMessage toolRespMsg = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "read", "file-body")))
                .build();

        List<Message> history = List.of(
                new SystemMessage("sys"),
                new UserMessage("u1"),
                new AssistantMessage("a1"),
                toolCallMsg,
                toolRespMsg,
                new AssistantMessage("a2")
        );

        ContextCompactor compactor = new ContextCompactor(0.7);
        ContextCompactor.Result result = compactor.compact(
                history,
                (messages, tools, systemPrompt, control, onTextDelta) ->
                        AssistantTurn.of(new AssistantMessage("摘要"), StopReason.STOP),
                new RunControl()
        );

        assertTrue(result.compacted());
        for (int i = 1; i < result.messages().size(); i++) {
            if (result.messages().get(i) instanceof ToolResponseMessage) {
                assertInstanceOf(AssistantMessage.class, result.messages().get(i - 1));
            }
        }
    }

    @Test
    void autoAndManualCompactionUpdateAgentHistoryWhenExceeding80PercentOfModelLimit() {
        AtomicInteger callIndex = new AtomicInteger();
        // 设置 test-model 最大上下文为 100 Token，0.8 阈值为 80 Token，压缩比例 0.7（70%）
        ContextCompactor compactor = new ContextCompactor(
                0.7,
                0.8,
                100,
                Map.of("test-model", 100)
        );
        JotaroAgent agent = new JotaroAgent(
                "system",
                List.of(),
                (messages, tools, systemPrompt, control, onTextDelta) ->
                        AssistantTurn.of(new AssistantMessage("回复内容-" + callIndex.incrementAndGet()), StopReason.STOP),
                10,
                40,
                ToolExecutionMode.SEQUENTIAL,
                null,
                compactor
        );
        agent.useModel("test-model", (messages, tools, systemPrompt, control, onTextDelta) ->
                AssistantTurn.of(new AssistantMessage("长回复内容用于推高Token数量-" + callIndex.incrementAndGet()), StopReason.STOP));

        agent.prompt("第一个问题内容足够长以便累计Token数量");
        agent.prompt("第二个问题内容足够长以便累计Token数量");
        // 第三次提问前，累计 Token 已超过 100 * 0.8 = 80，自动触发子代理压缩前 70% 历史
        agent.prompt("第三个问题内容足够长以便触发自动上下文压缩");

        boolean hasSummary = agent.getMessages().stream()
                .anyMatch(m -> m.getText() != null && m.getText().contains("[历史上下文压缩摘要"));
        assertTrue(hasSummary, "Expected auto-compaction summary in agent messages");
        assertFalse(agent.isRunning());
    }
}
