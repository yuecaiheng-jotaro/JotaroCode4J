package org.jotaro.jotarocode.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jotaro.jotarocode.agent.runtime.AssistantTurn;
import org.jotaro.jotarocode.agent.runtime.ContextCompactor;
import org.jotaro.jotarocode.agent.runtime.RunControl;
import org.jotaro.jotarocode.agent.runtime.StopReason;
import org.jotaro.jotarocode.config.JotaroAgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextManagerTest {

    @TempDir
    Path temp;

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

    @Test
    void persistsEventsInSqliteAndResumesSessionAcrossRestarts() {
        JotaroAgentProperties properties = new JotaroAgentProperties();
        properties.getSqlite().setPath(temp.resolve(".jotaro/jotaro.db").toString());

        SqliteContextStore store1 = new SqliteContextStore();
        store1.bind(properties, new ObjectMapper());

        ContextManager original = new ContextManager("sess-alpha-1234", store1, new ContextCompactor(0.7), ContextPipeline.Options.defaults());
        original.record(new SystemMessage("sys"));
        original.record(new UserMessage("帮我排查一下下单接口超时的原因"));
        original.record(new AssistantMessage("我先查看一下 OrderController 和下游依赖"));

        // 模拟进程重启后新建 SqliteContextStore 与新的空会话，通过 /resume 按前缀恢复历史上下文
        SqliteContextStore store2 = new SqliteContextStore();
        store2.bind(properties, new ObjectMapper());

        List<ContextStore.SessionSummary> sessions = store2.listSessions(10);
        assertEquals(1, sessions.size());
        assertEquals("sess-alpha-1234", sessions.get(0).sessionId());
        assertTrue(sessions.get(0).title().contains("帮我排查一下下单接口超时"));

        ContextManager fresh = new ContextManager("sess-new-9999", store2, new ContextCompactor(0.7), ContextPipeline.Options.defaults());
        ContextManager.ContextSnapshot resumed = fresh.resume("sess-alpha", "deepseek-chat");

        assertEquals("sess-alpha-1234", resumed.sessionId());
        assertEquals(3, resumed.rawEventCount());
        assertEquals(3, resumed.activeMessageCount());
        assertEquals("帮我排查一下下单接口超时的原因", fresh.activeMessages().get(1).getText());

        // 恢复后继续追加消息，序号连续递增不冲突
        fresh.record(new UserMessage("继续查一下数据库连接池配置"));
        assertEquals(4, store2.events("sess-alpha-1234").size());
        assertEquals(4L, store2.events("sess-alpha-1234").get(3).getSeq());
    }

    @Test
    void startNewKeepsPreviousSessionAndBeginsAnEmptyOne() {
        InMemoryContextStore store = new InMemoryContextStore();
        ContextManager manager = new ContextManager("sess-old", store, new ContextCompactor(0.7), ContextPipeline.Options.defaults());
        manager.record(new SystemMessage("sys"));
        manager.record(new UserMessage("旧会话里的问题"));
        manager.record(new AssistantMessage("旧会话里的回答"));

        String created = manager.startNew("sys");

        assertNotEquals("sess-old", created);
        assertEquals(created, manager.sessionId());
        assertEquals(1, manager.activeMessages().size());
        assertEquals("sys", manager.activeMessages().get(0).getText());
        assertEquals(3, store.events("sess-old").size());

        ContextManager.ContextSnapshot resumed = manager.resume("sess-old", "deepseek-chat");
        assertEquals("sess-old", resumed.sessionId());
        assertEquals(3, resumed.rawEventCount());
        assertEquals("旧会话里的问题", manager.activeMessages().get(1).getText());
    }
}
