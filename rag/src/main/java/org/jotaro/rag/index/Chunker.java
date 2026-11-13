package org.jotaro.rag.index;

import java.util.ArrayList;
import java.util.List;

/** 按字数装箱，或按字面分隔符切开。字数模式先按空行分段，再装进固定长度，相邻两段保留重叠。 */
public final class Chunker {

    private final int size;
    private final int overlap;

    public Chunker() {
        this(700, 80);
    }

    public Chunker(int size, int overlap) {
        this.size = Math.max(200, size);
        this.overlap = Math.max(0, Math.min(overlap, this.size / 2));
    }

    public int size() {
        return size;
    }

    public int overlap() {
        return overlap;
    }

    public List<String> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').strip();
        List<String> pieces = new ArrayList<>();
        for (String paragraph : normalized.split("\n\\s*\n")) {
            String block = paragraph.strip();
            if (block.isEmpty()) {
                continue;
            }
            if (block.length() <= size) {
                pieces.add(block);
                continue;
            }
            for (int start = 0; start < block.length(); start += size - overlap) {
                int end = Math.min(block.length(), start + size);
                pieces.add(block.substring(start, end));
                if (end == block.length()) {
                    break;
                }
            }
        }
        List<String> packed = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String piece : pieces) {
            if (current.isEmpty()) {
                current.append(piece);
                continue;
            }
            if (current.length() + 2 + piece.length() <= size) {
                current.append("\n\n").append(piece);
                continue;
            }
            packed.add(current.toString());
            current.setLength(0);
            current.append(piece);
        }
        if (!current.isEmpty()) {
            packed.add(current.toString());
        }
        return packed;
    }

    /** 按字面分隔符切开。分隔符本身不留在段里，空段丢掉。 */
    public static List<String> splitByDelimiter(String text, String delimiter) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if (delimiter == null || delimiter.isEmpty()) {
            throw new IllegalArgumentException("分隔符不能为空");
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').strip();
        String needle = delimiter.replace("\r\n", "\n").replace('\r', '\n');
        List<String> parts = new ArrayList<>();
        int start = 0;
        int found = normalized.indexOf(needle, start);
        while (found >= 0) {
            addPiece(parts, normalized.substring(start, found));
            start = found + needle.length();
            found = normalized.indexOf(needle, start);
        }
        addPiece(parts, normalized.substring(start));
        return parts;
    }

    /** 把用户输入的 \n、\t、\r 换成真正的换行和制表符。 */
    public static String unescape(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < raw.length(); index++) {
            char character = raw.charAt(index);
            if (character != '\\' || index + 1 >= raw.length()) {
                builder.append(character);
                continue;
            }
            char next = raw.charAt(++index);
            switch (next) {
                case 'n' -> builder.append('\n');
                case 't' -> builder.append('\t');
                case 'r' -> builder.append('\r');
                case '\\' -> builder.append('\\');
                default -> builder.append('\\').append(next);
            }
        }
        return builder.toString();
    }

    private static void addPiece(List<String> parts, String piece) {
        String text = piece.strip();
        if (!text.isEmpty()) {
            parts.add(text);
        }
    }
}
