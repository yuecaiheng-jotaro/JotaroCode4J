package org.jotaro.jotarocode.agent.tools.coding;

import org.jotaro.jotarocode.agent.tools.ToolContributor;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 读文本文件。{@code offset} 从 1 开始，默认最多 2000 行或 50KB。
 * 图片不附带内容，只返回一句说明，避免把二进制塞进对话。
 */
@Component
public class ReadTool implements ToolContributor {

    private static final Map<String, String> IMAGES = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "gif", "image/gif",
            "webp", "image/webp",
            "bmp", "image/bmp"
    );

    @org.springframework.ai.tool.annotation.Tool(
            name = "read",
            description = "Read the contents of a file. Supports text files and images (jpg, png, gif, webp, bmp). "
                    + "For text files, output is truncated to 2000 lines or 50KB (whichever is hit first). "
                    + "Use offset and limit for large files. When you need the full file, continue with offset until complete."
    )
    public String read(
            @ToolParam(description = "Path to the file to read (relative or absolute)") String path,
            @ToolParam(description = "Line number to start reading from (1-indexed)", required = false) Integer offset,
            @ToolParam(description = "Maximum number of lines to read", required = false) Integer limit
    ) {
        Path file = ToolPaths.resolve(path);
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("File not found: " + path);
        }
        if (Files.isDirectory(file)) {
            throw new IllegalArgumentException("Not a file: " + path);
        }
        String imageNote = imageNote(file);
        if (imageNote != null) {
            return imageNote;
        }
        int start = offset == null ? 1 : Math.max(1, offset);
        Integer lineLimit = limit == null ? null : Math.max(0, limit);
        try {
            if (Files.size(file) == 0) {
                if (offset != null && offset > 1) {
                    throw new IllegalArgumentException("Offset " + offset + " is beyond end of file (0 lines total)");
                }
                return "";
            }
            return readText(file, start, offset, lineLimit);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read " + path + ": " + exception.getMessage(), exception);
        }
    }

    private String readText(Path file, int start, Integer offset, Integer lineLimit) throws IOException {
        List<String> selected = new ArrayList<>();
        int selectedBytes = 0;
        int total = 0;
        boolean truncated = false;
        boolean firstLineTooBig = false;
        int firstLineBytes = 0;
        boolean userLimited = false;
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                total++;
                if (total < start || firstLineTooBig) {
                    continue;
                }
                if (lineLimit != null && selected.size() >= lineLimit) {
                    userLimited = true;
                    continue;
                }
                byte[] encoded = line.getBytes(StandardCharsets.UTF_8);
                if (selected.isEmpty() && encoded.length > ToolOutput.MAX_BYTES) {
                    firstLineTooBig = true;
                    firstLineBytes = encoded.length;
                    continue;
                }
                int extra = encoded.length + (selected.isEmpty() ? 0 : 1);
                if (selected.size() >= ToolOutput.MAX_LINES || selectedBytes + extra > ToolOutput.MAX_BYTES) {
                    truncated = true;
                    continue;
                }
                selected.add(line);
                selectedBytes += extra;
            }
        }
        if (start > total) {
            throw new IllegalArgumentException("Offset " + offset + " is beyond end of file (" + total + " lines total)");
        }
        if (firstLineTooBig) {
            return "[Line " + start + " is " + ToolOutput.formatSize(firstLineBytes)
                    + ", exceeds " + ToolOutput.formatSize(ToolOutput.MAX_BYTES)
                    + " limit. Use bash to read it in smaller pieces.]";
        }
        String body = String.join("\n", selected);
        if (truncated) {
            int endLine = start + selected.size() - 1;
            return body + "\n\n[Showing lines " + start + "-" + endLine + " of " + total
                    + ". Use offset=" + (endLine + 1) + " to continue.]";
        }
        if (userLimited) {
            int shownUntil = start + selected.size() - 1;
            int remaining = total - shownUntil;
            return body + "\n\n[" + remaining + " more lines in file. Use offset=" + (shownUntil + 1) + " to continue.]";
        }
        return body;
    }

    private static String imageNote(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        String mime = IMAGES.get(name.substring(dot + 1));
        if (mime == null) {
            return null;
        }
        return "Read image file [" + mime + "]\n"
                + "[Current model does not support images. The image will be omitted from this request.]";
    }
}
