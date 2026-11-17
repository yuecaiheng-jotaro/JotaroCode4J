package org.jotaro.jotarocode.tui;

import org.jotaro.jotarocode.agent.runtime.AgentEvent;
import org.jotaro.jotarocode.agent.runtime.AgentEventType;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * TUI 终端流式事件格式化器。
 * <p>
 * 负责将底层 {@link AgentEvent} 转换为终端滚动区的一行行带样式文本：
 * <ul>
 *   <li>已遇到换行符的完整行会立即由 {@link #format(AgentEvent)} 返回并打印到输入框上方滚动区。</li>
 *   <li>尚未遇到换行符的末尾半行保存在缓冲区中，由 {@link #livePlain()} 提供给输入框上方的动态状态条原地刷新。</li>
 *   <li>模型的思考过程（{@code reasoning=true}）使用灰色样式单独成段，首行带 {@code ✻ } 前缀，并在进入正式回复或工具调用前自动闭合。</li>
 * </ul>
 */
public final class AgentEventFormatter {

    /** 思考文本未包含换行符时的自动折行宽度上限。 */
    private static final int THINKING_COLUMNS = 48;

    /** 尚未换行的助手正文流式缓冲区。 */
    private final StringBuilder pending = new StringBuilder();
    /** 尚未换行的模型思考（reasoning）流式缓冲区。 */
    private final StringBuilder reasoningPending = new StringBuilder();
    /** 标记本轮助手消息是否已经收到过 {@code MESSAGE_UPDATE} 流式增量，避免 {@code MESSAGE_END} 时重复追加全文。 */
    private boolean streamed;
    /** 当前是否正处于模型思考阶段。 */
    private boolean thinking;
    /** 当前思考段落是否已经输出过首行 {@code ✻ } 标记。 */
    private boolean thinkingMarked;
    /** 下一行输出的助手正文是否需要带上段落起始圆点 {@link PromptStyle#mark()}。 */
    private boolean bullet = true;

    /**
     * 处理单个 Agent 运行时事件，提取所有已经闭合的完整文本行。
     *
     * @param event Agent 运行时事件
     * @return 本次事件产生的完整终端输出行列表（不含尚未换行的末尾半行）
     */
    public List<String> format(AgentEvent event) {
        List<String> lines = new ArrayList<>();
        // 新一轮推理开始，重置正文首行圆点标记
        if (event.getType() == AgentEventType.TURN_START) {
            bullet = true;
            return lines;
        }
        // 流式思考内容更新：推入思考缓冲区并切出已完成的行
        if (event.getType() == AgentEventType.MESSAGE_UPDATE && event.isReasoning()) {
            if (event.getTextDelta() != null) {
                thinking = true;
                reasoningPending.append(event.getTextDelta());
            }
            drainReasoning(lines, false);
            return lines;
        }
        // 流式正文内容更新：先闭合思考段，再推入正文缓冲区
        if (event.getType() == AgentEventType.MESSAGE_UPDATE) {
            closeThinking(lines);
            streamed = true;
            if (event.getTextDelta() != null) {
                pending.append(event.getTextDelta());
            }
            drain(lines, false);
            return lines;
        }
        // 助手消息结束：若此前未走流式增量，则一次性写入完整文本，并强制刷出剩余半行
        if (event.getType() == AgentEventType.MESSAGE_END && event.getMessage() instanceof AssistantMessage assistant) {
            closeThinking(lines);
            if (!streamed && assistant.getText() != null) {
                pending.append(assistant.getText());
            }
            drain(lines, true);
            streamed = false;
            return lines;
        }
        // 工具调用开始：刷清残留的思考与正文缓冲，输出单行工具调用摘要（subagent 由 SubagentPane 独立渲染）
        if (event.getType() == AgentEventType.TOOL_EXECUTION_START) {
            closeThinking(lines);
            drain(lines, true);
            bullet = true;
            if (!"subagent".equals(event.getToolName())) {
                lines.add(PromptStyle.tool("  " + ToolGlance.call(event.getToolName(), event.getArguments())));
            }
            return lines;
        }
        // 工具调用结束：输出工具结果单行预览或失败标记
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
        // 整轮 Agent 结束：刷清所有缓冲，若触发步数上限则追加提示
        if (event.getType() == AgentEventType.AGENT_END) {
            closeThinking(lines);
            drain(lines, true);
            if (event.isStepLimitReached()) {
                lines.add(PromptStyle.dim("已达到最大步数"));
            }
        }
        return lines;
    }

    /**
     * 获取当前尚未换行的实时半行文本（思考进行中时优先返回思考文本），供输入框上方动态预览。
     *
     * @return 未换行的实时文本
     */
    public String livePlain() {
        if (thinking && !reasoningPending.isEmpty()) {
            return reasoningPending.toString();
        }
        return pending.toString();
    }

    /**
     * 判断当前实时半行是否属于模型思考内容（用于决定是否渲染为思考灰色样式）。
     *
     * @return 若正在输出思考半行则返回 {@code true}
     */
    public boolean liveThinking() {
        return thinking && !reasoningPending.isEmpty();
    }

    /**
     * 结束当前思考段落：将思考缓冲区剩余内容全部刷出，并追加一个空行与后续正文隔开。
     */
    private void closeThinking(List<String> lines) {
        if (!thinking && reasoningPending.isEmpty()) {
            return;
        }
        drainReasoning(lines, true);
        thinking = false;
        thinkingMarked = false;
        lines.add("");
    }

    /**
     * 从思考缓冲区中切出已换行的行或超过 {@link #THINKING_COLUMNS} 宽度的片段。
     *
     * @param lines     输出行收集列表
     * @param flushTail 是否强制将末尾不足一行的残留文本一并刷出
     */
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

    /**
     * 将单行思考文本加上前缀（首行 {@code ✻ }，后续行两个空格缩进）并着灰色推入输出列表。
     */
    private void addThinking(List<String> lines, String text) {
        if (text.isEmpty()) {
            return;
        }
        String prefix = thinkingMarked ? "  " : "✻ ";
        thinkingMarked = true;
        lines.add(PromptStyle.thinking(prefix + text));
    }

    /**
     * 从助手正文缓冲区中切出已换行的完整行。
     *
     * @param lines     输出行收集列表
     * @param flushTail 是否在消息结束时把末尾未换行的余下文本一并刷出
     */
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

    /**
     * 为单行助手正文添加首行圆点标记或二格缩进。
     */
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

    /**
     * 查找缓冲区中第一个换行符（{@code \n} 或 {@code \r}）的下标。
     */
    private static int indexOfNewline(StringBuilder text) {
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n' || text.charAt(index) == '\r') {
                return index;
            }
        }
        return -1;
    }

    /**
     * 空指针安全地将字符串转为非 null 值。
     */
    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
