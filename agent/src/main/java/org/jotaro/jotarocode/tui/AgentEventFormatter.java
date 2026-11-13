package org.jotaro.jotarocode.tui;

import org.jotaro.jotarocode.agent.runtime.AgentEvent;
import org.jotaro.jotarocode.agent.runtime.AgentEventType;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 Agent 事件整理成终端上的一行行文本。已经换行的内容立刻成为独立行。
 * 还没换行的半行留在 {@link #livePlain()}，由终端贴在输入框上方原地刷新。
 * 思考内容用灰色单独成段，写在正文前面。
 */
public final class AgentEventFormatter {

    private static final int THINKING_COLUMNS = 48;

    private final StringBuilder pending = new StringBuilder();
    private final StringBuilder reasoningPending = new StringBuilder();
    private boolean streamed;
    private boolean thinking;
    private boolean thinkingMarked;
    private boolean bullet = true;

    public List<String> format(AgentEvent event) {
        List<String> lines = new ArrayList<>();
        if (event.getType() == AgentEventType.TURN_START) {
            bullet = true;
            return lines;
        }
        if (event.getType() == AgentEventType.MESSAGE_UPDATE && event.isReasoning()) {
            if (event.getTextDelta() != null) {
                thinking = true;
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
            drain(lines, true);
            bullet = true;
            if (!"subagent".equals(event.getToolName())) {
                lines.add(PromptStyle.tool("  " + ToolGlance.call(event.getToolName(), event.getArguments())));
            }
            return lines;
        }
        if (event.getType() == AgentEventType.TOOL_EXECUTION_END) {
            bullet = true;
            if ("subagent".equals(event.getToolName())) {
                return lines;
            }
            String preview = ToolGlance.preview(event.getResult());
            if (Boolean.TRUE.equals(event.getError())) {
                lines.add(PromptStyle.dim("  ✗ " + (preview.isEmpty() ? safe(event.getToolName()) : preview)));
            } else if (!preview.isEmpty()) {
                lines.add(PromptStyle.dim("  ⎿  " + preview));
            }
            return lines;
        }
        if (event.getType() == AgentEventType.AGENT_END) {
            closeThinking(lines);
            drain(lines, true);
            if (event.isStepLimitReached()) {
                lines.add(PromptStyle.dim("已达到最大步数"));
            }
        }
        return lines;
    }

    /** 当前还没换行的半行。思考进行中时返回思考文本。 */
    public String livePlain() {
        if (thinking && !reasoningPending.isEmpty()) {
            return reasoningPending.toString();
        }
        return pending.toString();
    }

    public boolean liveThinking() {
        return thinking && !reasoningPending.isEmpty();
    }

    private void closeThinking(List<String> lines) {
        if (!thinking && reasoningPending.isEmpty()) {
            return;
        }
        drainReasoning(lines, true);
        thinking = false;
        thinkingMarked = false;
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

    private void addThinking(List<String> lines, String text) {
        if (text.isEmpty()) {
            return;
        }
        String prefix = thinkingMarked ? "  " : "✻ ";
        thinkingMarked = true;
        lines.add(PromptStyle.thinking(prefix + text));
    }

    private void drain(List<String> lines, boolean flushTail) {
        int newline;
        while ((newline = indexOfNewline(pending)) >= 0) {
            lines.add(styleLine(pending.substring(0, newline)));
            int drop = pending.charAt(newline) == '\r' ? 2 : 1;
            pending.delete(0, Math.min(pending.length(), newline + drop));
        }
        if (flushTail && !pending.isEmpty()) {
            lines.add(styleLine(pending.toString()));
            pending.setLength(0);
        }
    }

    private String styleLine(String text) {
        if (text.isEmpty()) {
            return "";
        }
        if (bullet) {
            bullet = false;
            return PromptStyle.mark() + text;
        }
        return "  " + text;
    }

    private static int indexOfNewline(StringBuilder text) {
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n' || text.charAt(index) == '\r') {
                return index;
            }
        }
        return -1;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
