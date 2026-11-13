package org.jotaro.rag.index;

import java.util.ArrayList;
import java.util.List;

/** 先按空行切开，再按长度装箱。箱子之间保留一小段重叠，避免句子被切在边界上就搜不到。 */
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
}
