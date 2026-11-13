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
 * 精确替换。每段 {@code oldText} 在换行统一成 LF 之后必须刚好出现一次，多段之间不能重叠。
 * 对不上就失败，不会做模糊匹配。
 */
@Component
public class EditTool implements ToolContributor {

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
        synchronized (FileMutationLock.of(file)) {
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("Could not edit file: " + path + ".");
            }
            try {
                String raw = Files.readString(file);
                boolean bom = raw.startsWith("\uFEFF");
                String original = bom ? raw.substring(1) : raw;
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

    private static String toLf(String text) {
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }

    public record TextEdit(
            @JsonPropertyDescription("Exact text for one targeted replacement. It must be unique in the original file and must not overlap with any other edits[].oldText in the same call.")
            String oldText,
            @JsonPropertyDescription("Replacement text for this targeted edit.")
            String newText
    ) {
    }

    private record Span(int start, int length, String replacement, int editIndex) {
    }
}
