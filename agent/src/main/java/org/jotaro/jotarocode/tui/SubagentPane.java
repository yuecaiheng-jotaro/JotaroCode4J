package org.jotaro.jotarocode.tui;

import org.jotaro.jotarocode.agent.runtime.SubagentActivity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TUI 子代理（Subagent）嵌套进度与输出渲染器。
 * <p>
 * 将子代理运行期间产生的 {@link SubagentActivity} 实时整理为带缩进的终端文本行（包括子代理名称、任务描述、
 * 四格缩进的工具调用与结果预览、流式正文输出及完成/失败收尾），并在输入框上方状态栏提供当前焦点子代理的实时动作说明。
 * 当多个子代理并行执行时，按各自的 {@code activity.id()} 独立维护文本缓冲与状态。
 */
final class SubagentPane {

    /** 各子代理尚未遇到换行符的流式文本缓冲区（key 为子代理运行 ID）。 */
    private final Map<String, StringBuilder> pending = new LinkedHashMap<>();
    /** 记录各子代理是否已经通过流式 {@code TEXT} 输出过内容，避免在 {@code FINISHED} 时重复打印结果摘要。 */
    private final Map<String, Boolean> spoken = new LinkedHashMap<>();
    /** 各活跃子代理当前的单行状态描述（key 为子代理运行 ID）。 */
    private final Map<String, String> statuses = new LinkedHashMap<>();
    /** 当前在输入框上方状态栏展示的最新活跃子代理 ID。 */
    private String focus;

    /**
     * 接收并处理一条子代理活动事件，返回本次应打印到终端滚动区的完整文本行。
     *
     * @param activity 子代理活动事件
     * @return 格式化后的终端输出行列表
     */
    List<String> accept(SubagentActivity activity) {
        if (activity == null || activity.id() == null) {
            return List.of();
        }
        return switch (activity.kind()) {
            case STARTED -> start(activity);
            case TOOL -> tool(activity);
            case TOOL_RESULT -> List.of(PromptStyle.dim("    ⎿  " + shown(activity.detail())));
            case TEXT -> text(activity);
            case THINKING -> thinking(activity);
            case FINISHED -> finish(activity, true);
            case FAILED -> finish(activity, false);
        };
    }

    /**
     * 获取当前焦点子代理的实时状态描述（显示在输入框上方）；当没有任何正在运行的子代理时返回 {@code null}。
     */
    String status() {
        if (focus == null) {
            return null;
        }
        return statuses.get(focus);
    }

    /** 处理子代理启动事件：初始化缓冲并返回子代理标题行与任务简述。 */
    private List<String> start(SubagentActivity activity) {
        pending.put(activity.id(), new StringBuilder());
        spoken.put(activity.id(), false);
        focus(activity, activity.agent() + " · " + shown(activity.task()));
        List<String> lines = new ArrayList<>();
        lines.add(PromptStyle.mark() + activity.agent());
        if (activity.task() != null && !activity.task().isBlank()) {
            lines.add(PromptStyle.dim("  " + shown(activity.task())));
        }
        return lines;
    }

    /** 处理子代理工具调用事件：解析工具名与参数，更新状态栏并返回四格缩进的工具调用行。 */
    private List<String> tool(SubagentActivity activity) {
        String raw = activity.detail() == null ? "" : activity.detail();
        int split = raw.indexOf('\n');
        String name = split < 0 ? raw : raw.substring(0, split);
        String arguments = split < 0 ? "" : raw.substring(split + 1);
        String glance = ToolGlance.call(name, arguments);
        focus(activity, activity.agent() + " · " + glance);
        return List.of(PromptStyle.tool("    " + glance));
    }

    /** 处理子代理流式文本事件：切出已换行的完整行，并将未换行的尾部更新到状态栏。 */
    private List<String> text(SubagentActivity activity) {
        StringBuilder buffer = pending.computeIfAbsent(activity.id(), ignored -> new StringBuilder());
        if (activity.detail() != null) {
            buffer.append(activity.detail());
        }
        spoken.put(activity.id(), true);
        List<String> lines = drain(buffer, false);
        String tail = buffer.toString().replace('\n', ' ').strip();
        if (!tail.isEmpty()) {
            focus(activity, activity.agent() + " · " + ToolGlance.preview(tail));
        }
        return lines;
    }

    /** 处理子代理思考事件：更新状态栏为“正在想”。 */
    private List<String> thinking(SubagentActivity activity) {
        focus(activity, activity.agent() + " · 正在想");
        return List.of();
    }

    /**
     * 处理子代理结束或失败事件：
     * 刷出残留文本缓冲；若此前未流式输出过正文，则最多截取前 8 行结果摘要打印；最后清理该子代理的状态并将焦点切回其余仍在运行的子代理。
     */
    private List<String> finish(SubagentActivity activity, boolean ok) {
        StringBuilder buffer = pending.getOrDefault(activity.id(), new StringBuilder());
        List<String> lines = new ArrayList<>(drain(buffer, true));
        if (!Boolean.TRUE.equals(spoken.get(activity.id())) && activity.detail() != null && !activity.detail().isBlank()) {
            int count = 0;
            for (String line : activity.detail().split("\\R", -1)) {
                if (line.isBlank()) {
                    continue;
                }
                if (count == 8) {
                    lines.add(PromptStyle.dim("  …"));
                    break;
                }
                lines.add("  " + line);
                count++;
            }
        }
        lines.add(ok ? PromptStyle.dim("  ⎿ 完成") : PromptStyle.dim("  ✗ " + shown(activity.detail())));
        pending.remove(activity.id());
        spoken.remove(activity.id());
        statuses.remove(activity.id());
        if (activity.id().equals(focus)) {
            focus = statuses.isEmpty() ? null : statuses.keySet().stream().reduce((first, second) -> second).orElse(null);
        }
        return lines;
    }

    /** 从子代理文本缓冲区切出已换行的行。 */
    private List<String> drain(StringBuilder buffer, boolean flush) {
        List<String> lines = new ArrayList<>();
        int newline;
        while ((newline = indexOfNewline(buffer)) >= 0) {
            add(lines, buffer.substring(0, newline));
            int drop = buffer.charAt(newline) == '\r' ? 2 : 1;
            buffer.delete(0, Math.min(buffer.length(), newline + drop));
        }
        if (flush && !buffer.isEmpty()) {
            add(lines, buffer.toString());
            buffer.setLength(0);
        }
        return lines;
    }

    /** 为非空的子代理输出行加上两个空格缩进。 */
    private static void add(List<String> lines, String text) {
        if (!text.isEmpty()) {
            lines.add("  " + text);
        }
    }

    /** 将指定子代理设为当前状态栏焦点并更新其状态描述。 */
    private void focus(SubagentActivity activity, String status) {
        focus = activity.id();
        statuses.put(activity.id(), status);
    }

    /** 生成单行截断预览文本。 */
    private static String shown(String text) {
        String preview = ToolGlance.preview(text);
        return preview.isEmpty() ? "" : preview;
    }

    /** 查找缓冲区中第一个换行符的下标。 */
    private static int indexOfNewline(StringBuilder text) {
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n' || text.charAt(index) == '\r') {
                return index;
            }
        }
        return -1;
    }
}
