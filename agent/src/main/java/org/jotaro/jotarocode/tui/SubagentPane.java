package org.jotaro.jotarocode.tui;

import org.jotaro.jotarocode.agent.runtime.SubagentActivity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把子代理画成嵌套块：标题、任务、缩进的工具，以及它自己的文字。
 * 多个子代理并行时，每一块用自己的编号分开。
 */
final class SubagentPane {

    private final Map<String, StringBuilder> pending = new LinkedHashMap<>();
    private final Map<String, Boolean> spoken = new LinkedHashMap<>();
    private final Map<String, String> statuses = new LinkedHashMap<>();
    private String focus;

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

    /** 输入框上方的当前动作。没有正在跑的子代理时返回空。 */
    String status() {
        if (focus == null) {
            return null;
        }
        return statuses.get(focus);
    }

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

    private List<String> tool(SubagentActivity activity) {
        String raw = activity.detail() == null ? "" : activity.detail();
        int split = raw.indexOf('\n');
        String name = split < 0 ? raw : raw.substring(0, split);
        String arguments = split < 0 ? "" : raw.substring(split + 1);
        String glance = ToolGlance.call(name, arguments);
        focus(activity, activity.agent() + " · " + glance);
        return List.of(PromptStyle.tool("    " + glance));
    }

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

    private List<String> thinking(SubagentActivity activity) {
        focus(activity, activity.agent() + " · 正在想");
        return List.of();
    }

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

    private static void add(List<String> lines, String text) {
        if (!text.isEmpty()) {
            lines.add("  " + text);
        }
    }

    private void focus(SubagentActivity activity, String status) {
        focus = activity.id();
        statuses.put(activity.id(), status);
    }

    private static String shown(String text) {
        String preview = ToolGlance.preview(text);
        return preview.isEmpty() ? "" : preview;
    }

    private static int indexOfNewline(StringBuilder text) {
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n' || text.charAt(index) == '\r') {
                return index;
            }
        }
        return -1;
    }
}
