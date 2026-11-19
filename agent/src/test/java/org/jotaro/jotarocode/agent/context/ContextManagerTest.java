package org.jotaro.jotarocode.agent.context;

import org.jotaro.jotarocode.agent.runtime.AssistantTurn;
import org.jotaro.jotarocode.agent.runtime.ContextCompactor;
import org.jotaro.jotarocode.agent.runtime.RunControl;
import org.jotaro.jotarocode.agent.runtime.StopReason;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextManagerTest {

    @Test
    void prunesOldOversizedToolOutputsWhileKeepingRecentOnesAndPreservingRawLog() {
        InMemoryContextStore store = new InMemoryContextStore();
        ContextPipeline.Options options = new ContextPipeline.Options(true, 1, 100);
        ContextManager manager = new ContextManager("sess-prune", store, new ContextCompactor(0.7), options);

        manager.record(new SystemMessage("sys"));
        String bigOldOutput = "A".repeat(300);
        String bigRecentOutput = "B".repeat(300);

        manager.record(new UserMessage("step 1"));
        manager.record(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("1", "function", "read", "{}")))
                .build());
        manager.record(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("1", "read", bigOldOutput)))
                .build());

        manager.record(new UserMessage("step 2"));
        manager.record(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("2", "function", "read", "{}")))
                .build());
        manager.record(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("2", "read", bigRecentOutput)))
                .build());

        List<Message> derived = manager.activeMessages();
        ToolResponseMessage older = (ToolResponseMessage) derived.get(3);
        ToolResponseMessage recent = (ToolResponseMessage) derived.get(6);

        assertTrue(older.getResponses().get(0).responseData().contains("已裁剪旧工具输出"));
        assertEquals(bigRecentOutput, recent.getResponses().get(0).responseData());

        // 原始不可变事件日志里仍完整保留了旧工具输出
        assertEquals(bigOldOutput, store.events("sess-prune").get(3).getToolResults().get(0).getResponseData());

        ContextManager.ContextSnapshot snapshot = manager.inspect("deepseek-chat");
        assertEquals(1, snapshot.prunedToolOutputs());
        assertEquals(7, snapshot.rawEventCount());
    }

    @Test
    void anchorsGoalAndAppendsCompactionCheckpointWithoutDeletingRawEvents() {
        InMemoryContextStore store = new InMemoryContextStore();
        ContextManager manager = new ContextManager("sess-compact", store, new ContextCompactor(0.7), ContextPipeline.Options.defaults());

        manager.record(new SystemMessage("base-system"));
        manager.setGoal("重构订单模块并补齐单元测试");
        for (int i = 1; i <= 4; i++) {
            manager.record(new UserMessage("user-" + i));
            manager.record(new AssistantMessage("assistant-" + i));
        }

        int rawBefore = store.events("sess-compact").size();
        ContextCompactor.Result result = manager.compact(
                0.7,
                (messages, tools, systemPrompt, control, onTextDelta) ->
                        AssistantTurn.of(new AssistantMessage("前序重构进展摘要"), StopReason.STOP),
                new RunControl()
        );

        assertTrue(result.compacted());
        // 原始事件日志只增不减（追加了一条 COMPACTION 检查点事件）
        assertEquals(rawBefore + 1, store.events("sess-compact").size());

        List<Message> derived = manager.activeMessages();
        assertTrue(derived.get(0).getText().contains("base-system"));
        assertTrue(derived.get(0).getText().contains("重构订单模块并补齐单元测试"));
        assertTrue(derived.get(1).getText().contains("前序重构进展摘要"));

        ContextManager.ContextSnapshot snapshot = manager.inspect("glm-4.6");
        assertEquals(1, snapshot.compactionCount());
        assertEquals("重构订单模块并补齐单元测试", snapshot.goal());
        assertEquals(128_000, snapshot.modelContextLimit());
    }
}
