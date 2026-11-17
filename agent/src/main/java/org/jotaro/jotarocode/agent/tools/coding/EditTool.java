package org.jotaro.jotarocode.agent.tools.coding;

import org.jotaro.jotarocode.agent.tools.ToolContributor;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 文件精确替换工具（{@code edit}）。
 *
 * <p>核心设计原则与边界保证：
 * <ul>
 *   <li><b>唯一精确匹配</b>：先将文件与入参的换行符统一归一化为 {@code LF (\n)}，每处 {@code edits[].oldText} 在原文件中必须恰好出现 1 次（0 次或多次均报错拒绝，不做模糊匹配）。</li>
 *   <li><b>非重叠校验</b>：同一次调用中的多段修改均基于原始文件内容定位，按起始偏移量排序后检查区间是否重叠，若有重叠立即报错。</li>
 *   <li><b>逆序替换</b>：按匹配区间从文件末尾向文件头部逆序执行替换，确保靠前区间的字符偏移量不受后续替换长度变化的影响。</li>
 *   <li><b>格式保持与并发安全</b>：自动识别并保留原文件的 UTF-8 BOM 头及 CRLF/LF 换行风格；通过 {@link FileMutationLock} 保证同一文件的并发修改串行化。</li>
 * </ul>
 */
@Component
public class EditTool implements ToolContributor {

    @Override
    public String displayLabel() {
        return "Edit";
    }

    /**
     * 修改文件属于写操作，执行前需要用户在终端确认。
     */
    @Override
    public boolean requiresConfirmation() {
        return true;
    }

    /**
     * 对单个文件执行一处或多处精确文本替换。
     *
     * @param path  待编辑的文件路径（相对路径或绝对路径）
     * @param edits 替换块列表，每个替换块均针对原文件进行唯一定位且互不重叠
     * @return 替换成功的提示消息
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "edit",
            description = "Edit a single file using exact text replacement. Every edits[].oldText must match a unique, "
                    + "non-overlapping region of the original file. If two changes affect the same block or nearby lines, "
                    + "merge them into one edit instead of emitting overlapping edits."
    )
    public String edit(
            @ToolParam(description = "Path to the file to edit (relative or absolute)") String path,
            @ToolParam(description = "One or more targeted replacements. Each edit is matched against the original file, not incrementally.")
            List<TextEdit> edits
    ) {
        if (edits == null || edits.isEmpty()) {
            throw new IllegalArgumentException("edits must contain at least one replacement");
        }
        Path file = ToolPaths.resolve(path);
        // 按文件路径加锁，防止同一文件被多个 write/edit 并发覆写
        synchronized (FileMutationLock.of(file)) {
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("Could not edit file: " + path + ".");
            }
            try {
                String raw = Files.readString(file);
                // 检测并剥离 UTF-8 BOM 头，写回时按需还原
                boolean bom = raw.startsWith("\uFEFF");
                String original = bom ? raw.substring(1) : raw;
                // 记录原文件是否使用 CRLF 换行，匹配阶段统一转为 LF 处理
                boolean crlf = original.contains("\r\n");
                String normalized = toLf(original);
                String updated = apply(normalized, edits, path);
                String written = (bom ? "\uFEFF" : "") + (crlf ? updated.replace("\n", "\r\n") : updated);
                Files.writeString(file, written, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new IllegalStateException("Could not edit file: " + path + ". " + exception.getMessage(), exception);
            }
        }
        return "Successfully replaced " + edits.size() + " block(s) in " + path + ".";
    }

    /**
     * 在已归一化为 LF 的文件内容上校验所有编辑块并执行逆序替换。
     */
    private static String apply(String content, List<TextEdit> edits, String path) {
        List<Span> spans = new ArrayList<>();
        for (int index = 0; index < edits.size(); index++) {
            TextEdit edit = edits.get(index);
            if (edit == null || edit.oldText() == null || edit.oldText().isEmpty()) {
                throw new IllegalArgumentException(edits.size() == 1
                        ? "oldText must not be empty in " + path + "."
                        : "edits[" + index + "].oldText must not be empty in " + path + ".");
            }
            String oldText = toLf(edit.oldText());
            String newText = toLf(edit.newText() == null ? "" : edit.newText());
            int occurrences = count(content, oldText);
            if (occurrences == 0) {
                throw new IllegalArgumentException(edits.size() == 1
                        ? "Could not find the exact text in " + path
                        + ". The old text must match exactly including all whitespace and newlines."
                        : "Could not find edits[" + index + "] in " + path
                        + ". The oldText must match exactly including all whitespace and newlines.");
            }
            if (occurrences > 1) {
                throw new IllegalArgumentException(edits.size() == 1
                        ? "Found " + occurrences + " occurrences of the text in " + path
                        + ". The text must be unique. Please provide more context to make it unique."
                        : "Found " + occurrences + " occurrences of edits[" + index + "] in " + path
                        + ". Each oldText must be unique. Please provide more context to make it unique.");
            }
            spans.add(new Span(content.indexOf(oldText), oldText.length(), newText, index));
        }
        // 所有定位都基于同一份原文，排序后检查相邻区间是否重叠。
        spans.sort(Comparator.comparingInt(Span::start));
        for (int index = 1; index < spans.size(); index++) {
            Span previous = spans.get(index - 1);
            Span current = spans.get(index);
            if (previous.start + previous.length > current.start) {
                throw new IllegalArgumentException("edits[" + previous.editIndex + "] and edits[" + current.editIndex
                        + "] overlap in " + path + ". Merge them into one edit or target disjoint regions.");
            }
        }
        // 从文件末尾向前替换，已计算的前方偏移量不会被长度变化影响。
        StringBuilder builder = new StringBuilder(content);
        for (int index = spans.size() - 1; index >= 0; index--) {
            Span span = spans.get(index);
            builder.replace(span.start, span.start + span.length, span.replacement);
        }
        String updated = builder.toString();
        if (updated.equals(content)) {
            throw new IllegalArgumentException(edits.size() == 1
                    ? "No changes made to " + path + ". The replacement produced identical content."
                    : "No changes made to " + path + ". The replacements produced identical content.");
        }
        return updated;
    }

    /**
     * 统计子串 {@code needle} 在 {@code content} 中不重叠出现的次数。
     */
    private static int count(String content, String needle) {
        int count = 0;
        int from = 0;
        while (from <= content.length() - needle.length()) {
            int found = content.indexOf(needle, from);
            if (found < 0) {
                break;
            }
            count++;
            from = found + needle.length();
        }
        return count;
    }

    /**
     * 将文本中的 {@code CRLF} 与单独的 {@code CR} 统一转换为 {@code LF (\n)}。
     */
    private static String toLf(String text) {
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }

    /**
     * 单处精确替换操作参数。
     *
     * @param oldText 原文件中待替换的精确文本片段（必须在文件内唯一且与其他替换块不重叠）
     * @param newText 替换后的新文本
     */
    public record TextEdit(
            @JsonPropertyDescription("Exact text for one targeted replacement. It must be unique in the original file and must not overlap with any other edits[].oldText in the same call.")
            String oldText,
            @JsonPropertyDescription("Replacement text for this targeted edit.")
            String newText
    ) {
    }

    /**
     * 已在原文中唯一定位的替换区间。
     *
     * @param start       匹配区间在原文中的起始字符索引
     * @param length      原匹配文本长度
     * @param replacement 替换后的新文本
     * @param editIndex   对应入参 {@code edits} 列表中的原始下标（用于报错提示定位）
     */
    private record Span(int start, int length, String replacement, int editIndex) {
    }
}
