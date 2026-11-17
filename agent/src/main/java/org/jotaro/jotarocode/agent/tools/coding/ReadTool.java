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
 * 文件内容读取工具（{@code read}）。
 *
 * <p>分页与截断边界策略：
 * <ul>
 *   <li><b>行号基准</b>：{@code offset} 从 1 开始计数（1-indexed）；若 {@code offset} 超出文件总行数（或空文件传 {@code offset > 1}），抛出 {@link IllegalArgumentException}。</li>
 *   <li><b>双阈值自动截断</b>：单次最多读取 {@link ToolOutput#MAX_LINES}（2000 行）或 {@link ToolOutput#MAX_BYTES}（50KB），先触发者生效，并在末尾附带 {@code Use offset=N to continue} 续读指引。</li>
 *   <li><b>超长首行保护</b>：若起始行单行 UTF-8 字节数即超过 50KB，不返回正文，而是提示使用 {@code bash} 分片读取。</li>
 *   <li><b>图片文件降级</b>：识别常见图片扩展名（jpg/jpeg/png/gif/webp/bmp），直接返回 MIME 说明而不读取二进制内容，避免撑爆文本上下文。</li>
 * </ul>
 */
@Component
public class ReadTool implements ToolContributor {

    /** 常见图片文件扩展名到 MIME 类型的映射表。 */
    private static final Map<String, String> IMAGES = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "gif", "image/gif",
            "webp", "image/webp",
            "bmp", "image/bmp"
    );

    @Override
    public String displayLabel() {
        return "Read";
    }

    /**
     * 读取指定路径的文件内容，支持通过 {@code offset} 和 {@code limit} 分页读取大文件。
     *
     * @param path   文件路径（相对路径或绝对路径）
     * @param offset 起始行号（从 1 开始，可选，默认为第 1 行）
     * @param limit  本次最多读取的行数（可选）
     * @return 文件内容文本（若触发行数/字节截断或用户行数限制，末尾附带续读提示）
     */
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
        // 如果是图片文件，返回占位提示信息，避免把二进制乱码读入对话
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

    /**
     * 按行流式读取文本文件，统计总行数并执行 {@code offset}/{@code limit} 切片及 2000 行 / 50KB 阈值截断。
     */
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
                // 尚未到达起始行，或第一行就已经超过 50KB 时，仅继续空转统计文件总行数 total
                if (total < start || firstLineTooBig) {
                    continue;
                }
                // 已达到调用方指定的 limit 行数上限，继续空转统计剩余行数
                if (lineLimit != null && selected.size() >= lineLimit) {
                    userLimited = true;
                    continue;
                }
                byte[] encoded = line.getBytes(StandardCharsets.UTF_8);
                // 若起始行单行即超过 50KB，记录标记并提示用 bash 分段处理
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

    /**
     * 根据文件扩展名判断是否为图片文件；若是则返回对应的提示文本，否则返回 {@code null}。
     */
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
