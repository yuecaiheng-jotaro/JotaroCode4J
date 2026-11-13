package org.jotaro.jotarocode.agent.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 不连模型。直接喂助手消息和工具结果，检查步数、并行、steering、follow-up 和 terminate。 */
class AgentLoopTest {

    @Test
    void plainAnswerEmitsLifecycleAndStops() {
        ScriptedStreamer streamer = new ScriptedStreamer()
                .add(messages -> AssistantTurn.of(new AssistantMessage("hello"), StopReason.STOP));
        List<AgentEvent> events = new ArrayList<>();

        run(base(List.of(new UserMessage("hi")), streamer, List.of()).build(), events::add);

        assertEquals(1, streamer.calls);
        assertEquals(List.of(
                AgentEventType.AGENT_START,
                AgentEventType.TURN_START,
                AgentEventType.MESSAGE_START,
                AgentEventType.MESSAGE_UPDATE,
                AgentEventType.MESSAGE_END,
                AgentEventType.TURN_END,
                AgentEventType.AGENT_END
        ), events.stream().map(AgentEvent::getType).toList());
        assertEquals("hello", events.get(3).getTextDelta());
    }

    @Test
    void toolCallThenFinalAnswer() {
        AtomicInteger executions = new AtomicInteger();
        AgentTool echo = tool("echo", null, arguments -> {
            executions.incrementAndGet();
            return "echo:" + arguments;
        });
        ScriptedStreamer streamer = new ScriptedStreamer()
                .add(messages -> AssistantTurn.of(toolCall("1", "echo", "{\"text\":\"hi\"}"), StopReason.TOOL_CALLS))
                .add(messages -> {
                    Message last = messages.get(messages.size() - 1);
                    ToolResponseMessage toolMessage = assertInstanceOf(ToolResponseMessage.class, last);
                    assertEquals("echo:{\"text\":\"hi\"}", toolMessage.getResponses().get(0).responseData());
                    return AssistantTurn.of(new AssistantMessage("done"), StopReason.STOP);
                });

        run(base(List.of(new UserMessage("use echo")), streamer, List.of(echo)).build(), event -> {
        });

        assertEquals(2, streamer.calls);
        assertEquals(1, executions.get());
    }

    @Test
    void terminateToolSkipsTheNextModelCall() {
        ScriptedStreamer streamer = new ScriptedStreamer()
                .add(messages -> AssistantTurn.of(toolCall("1", "terminate", "{}"), StopReason.TOOL_CALLS))
                .add(messages -> AssistantTurn.of(new AssistantMessage("should-not-run"), StopReason.STOP));

        run(base(List.of(new UserMessage("stop")), streamer, List.of(tool("terminate", null, arguments -> ""))).build(), event -> {
        });

        assertEquals(1, streamer.calls);
    }

    @Test
    void blockedToolIsNotExecutedAndTheModelSeesTheReason() {
        AtomicBoolean executed = new AtomicBoolean();
        ScriptedStreamer streamer = new ScriptedStreamer()
                .add(messages -> AssistantTurn.of(toolCall("1", "bash", "{}"), StopReason.TOOL_CALLS))
                .add(messages -> {
                    ToolResponseMessage toolMessage = assertInstanceOf(ToolResponseMessage.class, messages.get(messages.size() - 1));
                    assertEquals("bash is disabled", toolMessage.getResponses().get(0).responseData());
                    return AssistantTurn.of(new AssistantMessage("ok"), StopReason.STOP);
                });

        AgentLoopRequest request = base(List.of(new UserMessage("run")), streamer, List.of(tool("bash", null, arguments -> {
            executed.set(true);
            return "ran";
        }))).beforeToolCall(context -> BeforeToolCallResult.builder().block(true).reason("bash is disabled").build())
                .build();

        run(request, event -> {
        });

        assertFalse(executed.get());
        assertEquals(2, streamer.calls);
    }

    @Test
    void steeringQueuedDuringAToolIsInjectedBeforeTheNextCall() {
        PendingMessageQueue steering = new PendingMessageQueue();
        ScriptedStreamer streamer = new ScriptedStreamer()
                .add(messages -> AssistantTurn.of(toolCall("1", "work", "{}"), StopReason.TOOL_CALLS))
                .add(messages -> {
                    assertInstanceOf(UserMessage.class, messages.get(messages.size() - 1));
                    assertEquals("do this instead", ((UserMessage) messages.get(messages.size() - 1)).getText());
                    return AssistantTurn.of(new AssistantMessage("changed"), StopReason.STOP);
                });
        AgentTool work = tool("work", null, arguments -> {
            steering.enqueue(new UserMessage("do this instead"));
            return "worked";
        });

        run(base(List.of(new UserMessage("start")), streamer, List.of(work))
                .steeringMessages(steering::drain)
                .build(), event -> {
        });

        assertEquals(2, streamer.calls);
    }

    @Test
    void followUpRunsAfterTheAgentWouldStop() {
        PendingMessageQueue followUp = new PendingMessageQueue();
        followUp.enqueue(new UserMessage("also summarize"));
        ScriptedStreamer streamer = new ScriptedStreamer()
                .add(messages -> AssistantTurn.of(new AssistantMessage("answer"), StopReason.STOP))
                .add(messages -> {
                    assertEquals("also summarize", ((UserMessage) messages.get(messages.size() - 1)).getText());
                    return AssistantTurn.of(new AssistantMessage("summary"), StopReason.STOP);
                });

        run(base(List.of(new UserMessage("question")), streamer, List.of())
                .followUpMessages(followUp::drain)
                .build(), event -> {
        });

        assertEquals(2, streamer.calls);
    }

    @Test
    void finishTurnEndStopsEvenWhenToolsWouldContinue() {
        ScriptedStreamer streamer = new ScriptedStreamer()
                .add(messages -> AssistantTurn.of(toolCall("1", "echo", "{}"), StopReason.TOOL_CALLS))
                .add(messages -> AssistantTurn.of(new AssistantMessage("nope"), StopReason.STOP));

        run(base(List.of(new UserMessage("go")), streamer, List.of(tool("echo", null, arguments -> "ok")))
                .finishTurn(context -> TurnDecision.END)
                .build(), event -> {
        });

        assertEquals(1, streamer.calls);
    }

    @Test
    void finishTurnContinueRequestsAnotherModelCall() {
        ScriptedStreamer streamer = new ScriptedStreamer()
                .add(messages -> AssistantTurn.of(new AssistantMessage("partial"), StopReason.STOP))
                .add(messages -> AssistantTurn.of(new AssistantMessage("continued"), StopReason.STOP));
        AtomicInteger continues = new AtomicInteger();

        run(base(List.of(new UserMessage("go")), streamer, List.of())
                .finishTurn(context -> continues.getAndIncrement() == 0 ? TurnDecision.CONTINUE : null)
                .build(), event -> {
        });

        assertEquals(2, streamer.calls);
    }

    @Test
    void maxStepsStopsAfterTheFirstAssistantResponse() {
        ScriptedStreamer streamer = new ScriptedStreamer()
                .add(messages -> AssistantTurn.of(toolCall("1", "echo", "{}"), StopReason.TOOL_CALLS))
                .add(messages -> AssistantTurn.of(new AssistantMessage("second"), StopReason.STOP));
        List<AgentEvent> events = new ArrayList<>();

        run(base(List.of(new UserMessage("go")), streamer, List.of(tool("echo", null, arguments -> "ok")))
                .maxSteps(1)
                .build(), events::add);

        assertEquals(1, streamer.calls);
        assertTrue(events.get(events.size() - 1).isStepLimitReached());
    }

    @Test
    void truncatedToolCallIsNotExecuted() {
        AtomicBoolean executed = new AtomicBoolean();
        ScriptedStreamer streamer = new ScriptedStreamer()
                .add(messages -> AssistantTurn.of(toolCall("1", "echo", "{\"text\":"), StopReason.LENGTH))
                .add(messages -> {
                    ToolResponseMessage toolMessage = assertInstanceOf(ToolResponseMessage.class, messages.get(messages.size() - 1));
                    assertTrue(toolMessage.getResponses().get(0).responseData().contains("长度上限"));
                    return AssistantTurn.of(new AssistantMessage("retry"), StopReason.STOP);
                });

        run(base(List.of(new UserMessage("go")), streamer, List.of(tool("echo", null, arguments -> {
            executed.set(true);
            return "ran";
        }))).build(), event -> {
        });

        assertFalse(executed.get());
        assertEquals(2, streamer.calls);
    }

    @Test
    void unknownToolIsReportedAndTheLoopContinues() {
        ScriptedStreamer streamer = new ScriptedStreamer()
                .add(messages -> AssistantTurn.of(toolCall("1", "missing", "{}"), StopReason.TOOL_CALLS))
                .add(messages -> {
                    ToolResponseMessage toolMessage = assertInstanceOf(ToolResponseMessage.class, messages.get(messages.size() - 1));
                    assertTrue(toolMessage.getResponses().get(0).responseData().contains("不存在"));
                    return AssistantTurn.of(new AssistantMessage("fallback"), StopReason.STOP);
                });

        run(base(List.of(new UserMessage("go")), streamer, List.of()).build(), event -> {
        });

        assertEquals(2, streamer.calls);
    }

    @Test
    void parallelToolsKeepResultOrder() {
        ScriptedStreamer streamer = new ScriptedStreamer()
                .add(messages -> AssistantTurn.of(AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(
                                new AssistantMessage.ToolCall("1", "function", "first", "{}"),
                                new AssistantMessage.ToolCall("2", "function", "second", "{}")
                        ))
                        .build(), StopReason.TOOL_CALLS))
                .add(messages -> {
                    ToolResponseMessage toolMessage = assertInstanceOf(ToolResponseMessage.class, messages.get(messages.size() - 1));
                    assertEquals(List.of("first", "second"), toolMessage.getResponses().stream().map(ToolResponseMessage.ToolResponse::name).toList());
                    assertEquals(List.of("one", "two"), toolMessage.getResponses().stream().map(ToolResponseMessage.ToolResponse::responseData).toList());
                    return AssistantTurn.of(new AssistantMessage("done"), StopReason.STOP);
                });

        run(base(List.of(new UserMessage("both")), streamer, List.of(
                tool("first", ToolExecutionMode.PARALLEL, arguments -> "one"),
                tool("second", ToolExecutionMode.PARALLEL, arguments -> "two")
        )).toolExecution(ToolExecutionMode.PARALLEL).build(), event -> {
        });

        assertEquals(2, streamer.calls);
    }

    @Test
    void toolResultsStayInAssistantOrder() {
        ScriptedStreamer streamer = new ScriptedStreamer()
                .add(messages -> AssistantTurn.of(AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(
                                new AssistantMessage.ToolCall("1", "function", "missing", "{}"),
                                new AssistantMessage.ToolCall("2", "function", "echo", "{}")
                        ))
                        .build(), StopReason.TOOL_CALLS))
                .add(messages -> {
                    ToolResponseMessage toolMessage = assertInstanceOf(ToolResponseMessage.class, messages.get(messages.size() - 1));
                    assertEquals(List.of("missing", "echo"), toolMessage.getResponses().stream()
                            .map(ToolResponseMessage.ToolResponse::name)
                            .toList());
                    return AssistantTurn.of(new AssistantMessage("done"), StopReason.STOP);
                });

        run(base(List.of(new UserMessage("go")), streamer, List.of(tool("echo", null, arguments -> "ok")))
                .build(), event -> {
        });

        assertEquals(2, streamer.calls);
    }

    @Test
    void modelWindowKeepsTheSystemPromptAndTheTail() {
        List<Message> messages = List.of(
                new SystemMessage("system"),
                new UserMessage("u1"),
                new AssistantMessage("a1"),
                new UserMessage("u2"),
                new AssistantMessage("a2"),
                new UserMessage("u3")
        );
        ScriptedStreamer streamer = new ScriptedStreamer()
                .add(seen -> {
                    assertEquals(3, seen.size());
                    assertInstanceOf(SystemMessage.class, seen.get(0));
                    assertEquals("a2", seen.get(1).getText());
                    assertEquals("u3", seen.get(2).getText());
                    return AssistantTurn.of(new AssistantMessage("ok"), StopReason.STOP);
                });

        run(base(messages, streamer, List.of()).modelWindow(2).build(), event -> {
        });
    }

    @Test
    void abortDuringAToolSkipsTheNextModelCall() {
        RunControl control = new RunControl();
        ScriptedStreamer streamer = new ScriptedStreamer()
                .add(messages -> AssistantTurn.of(toolCall("1", "work", "{}"), StopReason.TOOL_CALLS))
                .add(messages -> AssistantTurn.of(new AssistantMessage("nope"), StopReason.STOP));

        run(base(List.of(new UserMessage("go")), streamer, List.of(tool("work", null, arguments -> {
            control.abort();
            return "stopped";
        }))).control(control).build(), event -> {
        });

        assertEquals(1, streamer.calls);
    }

    private static void run(AgentLoopRequest request, Consumer<AgentEvent> listener) {
        new AgentLoop(request, listener).run();
    }

    private static AgentLoopRequest.AgentLoopRequestBuilder base(List<Message> messages,
                                                                AssistantStreamer streamer,
                                                                List<AgentTool> tools) {
        return AgentLoopRequest.builder()
                .messages(messages)
                .streamer(streamer)
                .tools(tools)
                .maxSteps(8)
                .modelWindow(20)
                .toolExecution(ToolExecutionMode.SEQUENTIAL);
    }

    private static AgentTool tool(String name, ToolExecutionMode mode, Function<String, String> body) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ToolExecutionMode executionMode() {
                return mode;
            }

            @Override
            public String execute(String arguments) {
                return body.apply(arguments);
            }
        };
    }

    private static AssistantMessage toolCall(String id, String name, String arguments) {
        return AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, arguments)))
                .build();
    }

    private static final class ScriptedStreamer implements AssistantStreamer {
        private final List<Function<List<Message>, AssistantTurn>> steps = new ArrayList<>();
        private int calls;

        private ScriptedStreamer add(Function<List<Message>, AssistantTurn> step) {
            steps.add(step);
            return this;
        }

        @Override
        public AssistantTurn stream(List<Message> messages,
                                    List<AgentTool> tools,
                                    String systemPrompt,
                                    RunControl control,
                                    Consumer<String> onTextDelta) {
            if (calls >= steps.size()) {
                throw new AssertionError("unexpected model call " + (calls + 1));
            }
            AssistantTurn turn = steps.get(calls++).apply(messages);
            if (onTextDelta != null && turn.getMessage().getText() != null && !turn.getMessage().getText().isEmpty()) {
                onTextDelta.accept(turn.getMessage().getText());
            }
            return turn;
        }
    }
}
