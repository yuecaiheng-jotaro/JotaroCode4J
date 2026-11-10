package com.kama.piagent.tui;

import com.kama.piagent.agent.runtime.AgentEvent;
import com.kama.piagent.agent.runtime.AgentEventType;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 Agent 事件整理成终端上的一行行文本。流式增量先按换行切分，避免每个 token 独占一行。
 */
public final class AgentEventFormatter {

    private final StringBuilder pending = new StringBuilder();
    private boolean streamed;

    public List<String> format(AgentEvent event) {
        List<String> lines = new ArrayList<>();
        if (event.getType() == AgentEventType.MESSAGE_UPDATE) {
            streamed = true;
            if (event.getTextDelta() != null) {
                pending.append(event.getTextDelta());
            }
            drain(lines, false);
            return lines;
        }
        if (event.getType() == AgentEventType.MESSAGE_END && event.getMessage() instanceof AssistantMessage assistant) {
            if (!streamed && assistant.getText() != null) {
                pending.append(assistant.getText());
            }
            drain(lines, true);
            streamed = false;
            return lines;
        }
        if (event.getType() == AgentEventType.TOOL_EXECUTION_START) {
            lines.add("→ " + safe(event.getToolName()) + " " + safe(event.getArguments()));
            return lines;
        }
        if (event.getType() == AgentEventType.TOOL_EXECUTION_END) {
            String mark = Boolean.TRUE.equals(event.getError()) ? "✗" : "←";
            lines.add(mark + " " + safe(event.getToolName()) + " " + abbreviate(safe(event.getResult())));
            return lines;
        }
        if (event.getType() == AgentEventType.AGENT_END) {
            drain(lines, true);
            lines.add(event.isStepLimitReached() ? "[已达到最大步数]" : "[完成]");
        }
        return lines;
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
