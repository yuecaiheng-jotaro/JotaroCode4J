package org.jotaro.jotarocode.agent.tools.coding;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 文本截断。头部截断给读文件，尾部截断给命令输出，避免一次工具结果撑爆上下文。 */
final class ToolOutput {

    static final int MAX_LINES = 2000;
    static final int MAX_BYTES = 50 * 1024;
    static final int GREP_MAX_LINE_LENGTH = 500;

    private ToolOutput() {
    }

    record Truncation(String content, boolean truncated, boolean firstLineExceedsLimit, int outputLines) {
    }

    static Truncation head(String content) {
        return head(content, MAX_LINES, MAX_BYTES);
    }

    static Truncation head(String content, int maxLines, int maxBytes) {
        List<String> lines = linesOf(content);
        if (lines.isEmpty()) {
            return new Truncation("", false, false, 0);
        }
        if (size(lines.get(0)) > maxBytes) {
            return new Truncation("", true, true, 0);
        }
        List<String> kept = new ArrayList<>();
        int bytes = 0;
        for (String line : lines) {
            int extra = size(line) + (kept.isEmpty() ? 0 : 1);
            if (kept.size() >= maxLines || bytes + extra > maxBytes) {
                return new Truncation(String.join("\n", kept), true, false, kept.size());
            }
            kept.add(line);
            bytes += extra;
        }
        return new Truncation(String.join("\n", kept), false, false, kept.size());
    }

    static Truncation tail(String content) {
        return tail(content, MAX_LINES, MAX_BYTES);
    }

    static Truncation tail(String content, int maxLines, int maxBytes) {
        List<String> lines = linesOf(content);
        if (lines.isEmpty()) {
            return new Truncation("", false, false, 0);
        }
        int start = lines.size();
        int bytes = 0;
        while (start > 0) {
            String line = lines.get(start - 1);
            int extra = size(line) + (lines.size() - start > 0 ? 1 : 0);
            if (lines.size() - (start - 1) > maxLines || bytes + extra > maxBytes) {
                break;
            }
            start--;
            bytes += extra;
        }
        List<String> kept = lines.subList(start, lines.size());
        return new Truncation(String.join("\n", kept), start > 0, false, kept.size());
    }

    static String formatSize(int bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.1fKB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1fMB", bytes / (1024.0 * 1024.0));
    }

    private static List<String> linesOf(String content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        String[] parts = content.split("\n", -1);
        int usable = content.endsWith("\n") ? parts.length - 1 : parts.length;
        List<String> lines = new ArrayList<>(usable);
        for (int index = 0; index < usable; index++) {
            lines.add(parts[index]);
        }
        return lines;
    }

    private static int size(String line) {
        return line.getBytes(StandardCharsets.UTF_8).length;
    }
}
