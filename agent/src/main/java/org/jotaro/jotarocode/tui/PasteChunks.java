package org.jotaro.jotarocode.tui;

import org.springframework.util.MimeType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * TUI 输入框粘贴内容折叠与展开管理器。
 * <p>
 * 负责在终端输入行内折叠大段粘贴文本与图片：
 * <ul>
 *   <li>短文本（不超过 {@link #MAX_INLINE_LINES} 行且不超过 {@link #MAX_INLINE_CHARS} 字符）直接原样留在输入框中。</li>
 *   <li>超长文本折叠为 {@code [Pasted text #1 +N lines]} 标签，图片路径折叠为 {@code [Image #1]} 标签，真实内容缓存在内存 {@link #chunks} 中。</li>
 *   <li>用户按退格（Backspace）或删除（Delete）键触碰到摘要标签任意位置时，由 {@link #covering(String, int)} 定位整段标签并一次性原子删除。</li>
 *   <li>用户回车提交时，由 {@link #expand(String)} 将行内所有标签还原为完整正文与图片附件列表发给模型。</li>
 * </ul>
 */
final class PasteChunks {

    /** 允许直接以内联文本留在输入框中的最大行数阈值。 */
    static final int MAX_INLINE_LINES = 5;
    /** 允许直接以内联文本留在输入框中的最大字符数阈值。 */
    static final int MAX_INLINE_CHARS = 800;

    /** 粘贴长文本摘要标签的自增序号。 */
    private int textSequence = 1;
    /** 粘贴图片摘要标签的自增序号。 */
    private int imageSequence = 1;
    /** 摘要标签（如 {@code [Pasted text #1 +10 lines]}）到真实数据块的映射表。 */
    private final Map<String, Chunk> chunks = new LinkedHashMap<>();

    /**
     * 登记一段粘贴的文本：短文本原样返回，超长文本缓存后返回折叠摘要标签。
     *
     * @param raw 原始粘贴文本
     * @return 直接写入输入框的字符串（原文或折叠标签）
     */
    String text(String raw) {
        String body = raw == null ? "" : raw.replace("\r\n", "\n").replace('\r', '\n');
        if (body.isEmpty()) {
            return "";
        }
        int lines = lineCount(body);
        if (lines <= MAX_INLINE_LINES && body.length() <= MAX_INLINE_CHARS) {
            return body;
        }
        String label = "[Pasted text #" + textSequence++ + " +" + lines + " lines]";
        chunks.put(label, new Chunk(body, null));
        return label;
    }

    /**
     * 登记一张粘贴的图片路径，返回形如 {@code [Image #1]} 的折叠标签。
     *
     * @param path 图片本地路径
     * @return 图片折叠标签
     */
    String image(Path path) {
        String label = "[Image #" + imageSequence++ + "]";
        chunks.put(label, new Chunk(null, path));
        return label;
    }

    /**
     * 判断给定输入行中是否包含已登记的粘贴折叠标签（用于区分普通斜杠命令与以 {@code /} 开头的粘贴内容）。
     *
     * @param line 待检查的输入行
     * @return 若包含任意已登记的粘贴标签则返回 {@code true}
     */
    boolean contains(String line) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        for (String label : chunks.keySet()) {
            if (line.contains(label)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将输入行中的所有粘贴折叠标签展开为真实文本与图片列表。
     * <p>
     * 若展开后文本为空白但附带了图片，会自动补上默认提示词 {@code "请看附图。"}。
     *
     * @param line 含有折叠标签的输入行
     * @return 展开后的完整正文与图片路径列表
     */
    Expanded expand(String line) {
        if (line == null || line.isEmpty()) {
            return new Expanded("", List.of());
        }
        StringBuilder text = new StringBuilder();
        List<Path> images = new ArrayList<>();
        int index = 0;
        while (index < line.length()) {
            String label = longestLabelAt(line, index);
            if (label == null) {
                text.append(line.charAt(index));
                index++;
                continue;
            }
            Chunk chunk = chunks.get(label);
            if (chunk.text != null) {
                text.append(chunk.text);
            }
            if (chunk.image != null) {
                images.add(chunk.image);
            }
            index += label.length();
        }
        String body = text.toString();
        if (body.isBlank() && !images.isEmpty()) {
            body = "请看附图。";
        }
        return new Expanded(body, List.copyOf(images));
    }

    /**
     * 查找当前输入缓冲区中覆盖指定字符位置 {@code index} 的粘贴摘要标签范围。
     * <p>
     * 当用户按下退格或删除键且目标字符正好落在某个摘要标签内部时，返回该标签的起始下标与文本，以便整段一次性删掉。
     *
     * @param buffer 输入框当前完整字符串
     * @param index  将要删除的字符下标
     * @return 命中的标签范围 {@link Span}，未命中时返回 {@code null}
     */
    Span covering(String buffer, int index) {
        if (buffer == null || index < 0 || index >= buffer.length()) {
            return null;
        }
        Span found = null;
        for (String label : chunks.keySet()) {
            int from = 0;
            while (from <= index) {
                int at = buffer.indexOf(label, from);
                if (at < 0 || at > index) {
                    break;
                }
                if (index < at + label.length() && (found == null || label.length() > found.label.length())) {
                    found = new Span(at, label);
                }
                from = at + 1;
            }
        }
        return found;
    }

    /**
     * 统计文本的实际行数（末尾单独的换行符不额外多算一行）。
     */
    static int lineCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n') {
                lines++;
            }
        }
        if (text.charAt(text.length() - 1) == '\n') {
            lines--;
        }
        return Math.max(lines, 1);
    }

    /**
     * 判断粘贴进来的单行短文本是否是一个真实存在的本地支持格式图片文件路径（如向终端拖入图片文件）。
     */
    static boolean isImageFile(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty() || trimmed.indexOf('\n') >= 0 || trimmed.length() > 1024) {
            return false;
        }
        Path path = Path.of(trimmed);
        return Files.isRegularFile(path) && mime(path) != null;
    }

    /**
     * 根据文件扩展名推断 Spring AI 所需的图片 {@link MimeType}。
     */
    static MimeType mime(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) {
            return org.springframework.ai.content.Media.Format.IMAGE_PNG;
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return org.springframework.ai.content.Media.Format.IMAGE_JPEG;
        }
        if (name.endsWith(".gif")) {
            return org.springframework.ai.content.Media.Format.IMAGE_GIF;
        }
        if (name.endsWith(".webp")) {
            return org.springframework.ai.content.Media.Format.IMAGE_WEBP;
        }
        return null;
    }

    /**
     * 在输入行的指定偏移处匹配已登记的最长粘贴标签。
     */
    private String longestLabelAt(String line, int index) {
        String found = null;
        for (String label : chunks.keySet()) {
            if (line.startsWith(label, index) && (found == null || label.length() > found.length())) {
                found = label;
            }
        }
        return found;
    }

    /** 缓存的单个粘贴数据块（文本或图片二选一）。 */
    private record Chunk(String text, Path image) {
    }

    /** 输入缓冲区中某个粘贴摘要标签的起始偏移与标签字面量。 */
    record Span(int start, String label) {
    }

    /** 展开全部粘贴标签后得到的最终发送正文与图片路径集合。 */
    record Expanded(String text, List<Path> images) {
    }
}
