package org.jotaro.jotarocode.tui;

import org.jotaro.jotarocode.agent.runtime.AgentEvent;
import org.jotaro.jotarocode.agent.runtime.AgentEventType;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 Agent 事件整理成终端上的一行行文本。流式增量先按换行切分，避免每个 token 独占一行。
 * 思考内容用灰色单独成段，写在正文前面；一行还没结束时，攒满一截也先打出来，避免一直贴在输入框上。
 */
public final class AgentEventFormatter {

    private static final int THINKING_COLUMNS = 48;

    private final StringBuilder pending = new StringBuilder();
    private final StringBuilder reasoningPending = new StringBuilder();
    private boolean streamed;
    private boolean thinking;

    public List<String> format(AgentEvent event) {
        List<String> lines = new ArrayList<>();
        if (event.getType() == AgentEventType.MESSAGE_UPDATE && event.isReasoning()) {
            if (event.getTextDelta() != null) {
                if (!thinking) {
                    thinking = true;
                    lines.add(PromptStyle.thinking("思考"));
                }
                reasoningPending.append(event.getTextDelta());
            }
            drainReasoning(lines, false);
            return lines;
        }
        if (event.getType() == AgentEventType.MESSAGE_UPDATE) {
            closeThinking(lines);
            streamed = true;
            if (event.getTextDelta() != null) {
                pending.append(event.getTextDelta());
            }
            drain(lines, false);
            return lines;
        }
        if (event.getType() == AgentEventType.MESSAGE_END && event.getMessage() instanceof AssistantMessage assistant) {
            closeThinking(lines);
            if (!streamed && assistant.getText() != null) {
                pending.append(assistant.getText());
            }
            drain(lines, true);
            streamed = false;
            return lines;
        }
        if (event.getType() == AgentEventType.TOOL_EXECUTION_START) {
            closeThinking(lines);
            lines.add("→ " + safe(event.getToolName()) + " " + safe(event.getArguments()));
            return lines;
        }
        if (event.getType() == AgentEventType.TOOL_EXECUTION_END) {
            String mark = Boolean.TRUE.equals(event.getError()) ? "✗" : "←";
            lines.add(mark + " " + safe(event.getToolName()) + " " + abbreviate(safe(event.getResult())));
            return lines;
        }
        if (event.getType() == AgentEventType.AGENT_END) {
            closeThinking(lines);
            drain(lines, true);
            lines.add(event.isStepLimitReached() ? "[已达到最大步数]" : "[完成]");
        }
        return lines;
    }

    private void closeThinking(List<String> lines) {
        if (!thinking && reasoningPending.isEmpty()) {
            return;
        }
        drainReasoning(lines, true);
        thinking = false;
        lines.add("");
    }

    private void drainReasoning(List<String> lines, boolean flushTail) {
        int newline;
        while ((newline = indexOfNewline(reasoningPending)) >= 0) {
            addThinking(lines, reasoningPending.substring(0, newline));
            int drop = reasoningPending.charAt(newline) == '\r' ? 2 : 1;
            reasoningPending.delete(0, Math.min(reasoningPending.length(), newline + drop));
        }
        while (!flushTail && reasoningPending.length() >= THINKING_COLUMNS) {
            addThinking(lines, reasoningPending.substring(0, THINKING_COLUMNS));
            reasoningPending.delete(0, THINKING_COLUMNS);
        }
        if (flushTail && !reasoningPending.isEmpty()) {
            addThinking(lines, reasoningPending.toString());
            reasoningPending.setLength(0);
        }
    }

    private static void addThinking(List<String> lines, String text) {
        if (!text.isEmpty()) {
            lines.add(PromptStyle.thinking(text));
        }
    }

    private void drain(List<String> lines, boolean flushTail) {
        int newline;
        while ((newline = indexOfNewline(pending)) >= 0) {
            lines.add(pending.substring(0, newline));
            int drop = pending.charAt(newline) == '\r' ? 2 : 1;
            pending.delete(0, Math.min(pending.length(), newline + drop));
        }
        if (flushTail && !pending.isEmpty()) {
            lines.add(pending.toString());
            pending.setLength(0);
        }
    }

    private static int indexOfNewline(StringBuilder text) {
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n' || text.charAt(index) == '\r') {
                return index;
            }
        }
        return -1;
    }

    private static String abbreviate(String value) {
        String singleLine = value.replace('\n', ' ').replace('\r', ' ');
        if (singleLine.length() <= 180) {
            return singleLine;
        }
        return singleLine.substring(0, 180) + "...";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
