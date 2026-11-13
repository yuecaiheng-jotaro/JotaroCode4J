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
 * 粘贴进输入框的内容。短文本原样留下。过长的文本收成一行，图片收成一行，
 * 真正的内容留在这里，发送时再展开。退格碰到这一行时整段删掉。
 */
final class PasteChunks {

    static final int MAX_INLINE_LINES = 5;
    static final int MAX_INLINE_CHARS = 800;

    private int textSequence = 1;
    private int imageSequence = 1;
    private final Map<String, Chunk> chunks = new LinkedHashMap<>();

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

    String image(Path path) {
        String label = "[Image #" + imageSequence++ + "]";
        chunks.put(label, new Chunk(null, path));
        return label;
    }

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

    /** 光标落在摘要内部或紧挨着要删的那个字符上时，返回整段摘要。 */
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

    private String longestLabelAt(String line, int index) {
        String found = null;
        for (String label : chunks.keySet()) {
            if (line.startsWith(label, index) && (found == null || label.length() > found.length())) {
                found = label;
            }
        }
        return found;
    }

    private record Chunk(String text, Path image) {
    }

    record Span(int start, String label) {
    }

    record Expanded(String text, List<Path> images) {
    }
}
