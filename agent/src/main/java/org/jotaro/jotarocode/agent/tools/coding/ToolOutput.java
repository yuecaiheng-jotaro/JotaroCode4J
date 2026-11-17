package org.jotaro.jotarocode.agent.tools.coding;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 工具输出文本的按行与按字节截断工具类。
 *
 * <p>提供两种方向的截断策略，防止单次工具返回结果过大撑爆大模型上下文窗口：
 * <ul>
 *   <li><b>头部截断（{@link #head}）</b>：从第 1 行向后保留最多 {@link #MAX_LINES}（2000 行）或 {@link #MAX_BYTES}（50KB），适用于读文件、列目录、搜索结果列表。</li>
 *   <li><b>尾部截断（{@link #tail}）</b>：从最后一行向前保留最多 {@link #MAX_LINES}（2000 行）或 {@link #MAX_BYTES}（50KB），适用于 Shell 命令输出（通常报错堆栈和最终汇总在末尾）。</li>
 * </ul>
 */
final class ToolOutput {

    /** 单次工具输出默认允许的最大行数（2000 行）。 */
    static final int MAX_LINES = 2000;
    /** 单次工具输出默认允许的最大 UTF-8 字节数（50KB）。 */
    static final int MAX_BYTES = 50 * 1024;
    /** {@code grep} 工具单行展示的最大字符长度（500 字符）。 */
    static final int GREP_MAX_LINE_LENGTH = 500;

    private ToolOutput() {
    }

    /**
     * 截断结果封装。
     *
     * @param content               截断后保留的文本内容
     * @param truncated             是否发生了行数或字节数截断
     * @param firstLineExceedsLimit 是否因为第一行本身就超过 {@code maxBytes} 导致一行都无法保留
     * @param outputLines           实际保留的输出行数
     */
    record Truncation(String content, boolean truncated, boolean firstLineExceedsLimit, int outputLines) {
    }

    /**
     * 按默认上限（2000 行 / 50KB）执行头部截断。
     */
    static Truncation head(String content) {
        return head(content, MAX_LINES, MAX_BYTES);
    }

    /**
     * 从文本开头逐行累加，直到达到 {@code maxLines} 或 {@code maxBytes} 上限为止。
     */
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

    /**
     * 按默认上限（2000 行 / 50KB）执行尾部截断。
     */
    static Truncation tail(String content) {
        return tail(content, MAX_LINES, MAX_BYTES);
    }

    /**
     * 从文本末尾倒序逐行累加，保留最后不超过 {@code maxLines} 且不超过 {@code maxBytes} 的行。
     */
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

    /**
     * 将字节数格式化为人类可读的单位（{@code B}、{@code KB} 或 {@code MB}）。
     */
    static String formatSize(int bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.1fKB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1fMB", bytes / (1024.0 * 1024.0));
    }

    /**
     * 按 {@code \n} 切分文本为行列表；若文本以单个换行符结尾，不将其后的空串算作额外空行。
     */
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

    /** 计算单行文本的 UTF-8 字节长度。 */
    private static int size(String line) {
        return line.getBytes(StandardCharsets.UTF_8).length;
    }
}
