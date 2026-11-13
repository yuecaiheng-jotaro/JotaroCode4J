package org.jotaro.jotarocode.agent.subagent;

import org.jotaro.jotarocode.agent.runtime.AgentTool;
import org.jotaro.jotarocode.agent.runtime.AssistantTurn;
import org.jotaro.jotarocode.agent.runtime.BeforeToolCallResult;
import org.jotaro.jotarocode.agent.runtime.StopReason;
import org.jotaro.jotarocode.agent.runtime.SubagentScope;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubagentRunnerTest {

    @Test
    void singleTaskReturnsTheChildTextAndHidesTheSubagentTool() {
        List<List<String>> seen = new ArrayList<>();
        String result = new SubagentRunner().execute(
                "explore",
                "where is main",
                List.of(),
                List.of(),
                SubagentCatalog.builtins(),
                environment((messages, tools, systemPrompt, control, onTextDelta) -> {
                    seen.add(tools.stream().map(AgentTool::name).toList());
                    assertEquals(2, messages.size());
                    assertEquals("where is main", ((UserMessage) messages.get(1)).getText());
                    assertTrue(messages.get(0).getText().contains("只读"));
                    return AssistantTurn.of(new AssistantMessage("src/Main.java"), StopReason.STOP);
                })
        );

        assertEquals("src/Main.java", result);
        assertEquals(List.of("ls", "read", "grep", "find"), seen.get(0));
    }

    @Test
    void chainReplacesPrevious() {
        String result = new SubagentRunner().execute(
                null,
                null,
                List.of(),
                List.of(
                        new SubagentRunner.Task("explore", "find it"),
                        new SubagentRunner.Task("general", "fix {previous}")
                ),
                SubagentCatalog.builtins(),
                environment((messages, tools, systemPrompt, control, onTextDelta) -> {
                    String task = ((UserMessage) messages.get(messages.size() - 1)).getText();
                    if (task.startsWith("fix ")) {
                        assertEquals("fix src/Main.java", task);
                        assertTrue(tools.stream().map(AgentTool::name).anyMatch("write"::equals));
                        assertTrue(tools.stream().noneMatch(tool -> "subagent".equals(tool.name())));
                        return AssistantTurn.of(new AssistantMessage("fixed"), StopReason.STOP);
                    }
                    return AssistantTurn.of(new AssistantMessage("src/Main.java"), StopReason.STOP);
                })
        );

        assertTrue(result.contains("src/Main.java"));
        assertTrue(result.contains("fixed"));
    }

    @Test
    void parallelKeepsInputOrderWhenOneChildFails() {
        String result = new SubagentRunner().execute(
                null,
                null,
                List.of(
                        new SubagentRunner.Task("explore", "good"),
                        new SubagentRunner.Task("general", "bad")
                ),
                List.of(),
                SubagentCatalog.builtins(),
                environment((messages, tools, systemPrompt, control, onTextDelta) -> {
                    String task = ((UserMessage) messages.get(messages.size() - 1)).getText();
                    if ("bad".equals(task)) {
                        return AssistantTurn.error("boom");
                    }
                    return AssistantTurn.of(new AssistantMessage("ok"), StopReason.STOP);
                })
        );

        assertTrue(result.indexOf("ok") < result.indexOf("失败: boom"));
    }

    @Test
    void unknownAgentAndEmptyCallFailBeforeAModelCall() {
        SubagentRunner runner = new SubagentRunner();
        List<SubagentDefinition> agents = SubagentCatalog.builtins();
        SubagentRunner.Environment environment = environment((messages, tools, systemPrompt, control, onTextDelta) -> {
            throw new AssertionError("不应调用模型");
        });

        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> runner.execute("missing", "go", List.of(), List.of(), agents, environment));
        assertTrue(unknown.getMessage().contains("explore"));
        assertThrows(IllegalArgumentException.class,
                () -> runner.execute(null, null, List.of(), List.of(), agents, environment));
    }

    @Test
    void definitionModelWinsAndParentConfirmationStillRuns() {
        AtomicReference<String> model = new AtomicReference<>();
        AtomicReference<String> confirmed = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        SubagentDefinition reader = new SubagentDefinition("reader", "reads", List.of("read"), "glm-4.6", "ONLY-READ");
        SubagentScope.run(new SubagentScope.Context("deepseek-chat", context -> {
            confirmed.set(context.getToolCall().name());
            return BeforeToolCallResult.builder().block(true).reason("先不要读").build();
        }), () -> {
            String result = new SubagentRunner().execute(
                    "reader",
                    "read me",
                    List.of(),
                    List.of(),
                    List.of(reader),
                    new SubagentRunner.Environment(
                            List.of(tool("read"), tool("subagent")),
                            "deepseek-chat",
                            20,
                            name -> {
                                model.set(name);
                                return (messages, tools, systemPrompt, control, onTextDelta) -> {
                                    assertEquals(List.of("read"), tools.stream().map(AgentTool::name).toList());
                                    assertTrue(messages.get(0).getText().contains("ONLY-READ"));
                                    if (calls.incrementAndGet() == 1) {
                                        return AssistantTurn.of(toolCall("read"), StopReason.TOOL_CALLS);
                                    }
                                    return AssistantTurn.of(new AssistantMessage("stopped"), StopReason.STOP);
                                };
                            }
                    )
            );
            assertEquals("stopped", result);
        });

        assertEquals("glm-4.6", model.get());
        assertEquals("read", confirmed.get());
    }

    private static SubagentRunner.Environment environment(
            org.jotaro.jotarocode.agent.runtime.AssistantStreamer streamer) {
        return new SubagentRunner.Environment(
                List.of(tool("ls"), tool("read"), tool("grep"), tool("find"), tool("write"), tool("subagent")),
                "deepseek-chat",
                20,
                name -> {
                    assertEquals("deepseek-chat", name);
                    return streamer;
                }
        );
    }

    private static AgentTool tool(String name) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String execute(String arguments) {
                return "should-not-run";
            }
        };
    }

    private static AssistantMessage toolCall(String name) {
        return AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("1", "function", name, "{}")))
                .build();
    }
}
