package org.jotaro.rag.index;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档分段器（Chunker），支持“按字数滑动窗口 + 段落合并装箱”与“按字面分隔符切分”两种模式。
 * <p>
 * 字数模式（{@link #split(String)}）的核心流程：
 * <ol>
 *   <li>先统一换行符并按空行（{@code \n\s*\n}）将全文拆分为自然段落；</li>
 *   <li>若单个自然段落长度不超过 {@code size}，则直接保留为独立碎片；若超过 {@code size}，则以步长 {@code size - overlap} 进行滑动窗口切分，使相邻两段保留 {@code overlap} 个字符的上下文重叠；</li>
 *   <li>最后对切出的碎片进行贪心装箱合并：只要当前分段加上 {@code "\n\n"} 和下一个碎片不超过 {@code size}，就合并到同一个分段中，避免产生大量过短的碎片段落。</li>
 * </ol>
 */
public final class Chunker {

    /** 单个分段的最大字符数上限（至少为 200） */
    private final int size;
    /** 超长段落滑动切分时，相邻两个片段之间的重叠字符数（范围 {@code [0, size / 2]}） */
    private final int overlap;

    /**
     * 使用默认参数（分段大小 700 字符、重叠 80 字符）构造分段器。
     */
    public Chunker() {
        this(700, 80);
    }

    /**
     * 使用指定的分段大小和重叠字符数构造分段器，并自动将参数收敛到安全边界内。
     *
     * @param size    目标分段最大字符数（小于 200 时按 200 处理）
     * @param overlap 相邻片段重叠字符数（自动限制在 {@code 0} 到 {@code size / 2} 之间，防止步长小于等于 0 导致死循环）
     */
    public Chunker(int size, int overlap) {
        this.size = Math.max(200, size);
        this.overlap = Math.max(0, Math.min(overlap, this.size / 2));
    }

    /**
     * 获取分段最大字符数。
     *
     * @return 分段最大字符数
     */
    public int size() {
        return size;
    }

    /**
     * 获取相邻片段的重叠字符数。
     *
     * @return 重叠字符数
     */
    public int overlap() {
        return overlap;
    }

    /**
     * 按“自然段落拆解 + 超长段落滑动窗口重叠切分 + 贪心装箱合并”策略将文本切分为分段列表。
     *
     * @param text 原始文档全文
     * @return 切分并合并后的文本分段列表；若输入为空白则返回空列表
     */
    public List<String> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        // 第一步：将 CRLF / CR 统一规范化为 LF，并去除首尾空白
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').strip();
        List<String> pieces = new ArrayList<>();
        // 第二步：按一个或多个空行拆分为自然段落
        for (String paragraph : normalized.split("\n\\s*\n")) {
            String block = paragraph.strip();
            if (block.isEmpty()) {
                continue;
            }
            // 自然段落长度在上限以内时，直接作为一个完整碎片保留
            if (block.length() <= size) {
                pieces.add(block);
                continue;
            }
            // 自然段落超长时，按步长 (size - overlap) 滑动窗口切分，保留相邻片段的上下文重叠
            for (int start = 0; start < block.length(); start += size - overlap) {
                int end = Math.min(block.length(), start + size);
                pieces.add(block.substring(start, end));
                // 已经覆盖到段落末尾时提前结束，避免尾部产生冗余重复小片段
                if (end == block.length()) {
                    break;
                }
            }
        }
        // 第三步：贪心装箱合并——将连续的短碎片用双换行 "\n\n" 拼接到一起，直到逼近 size 上限
        List<String> packed = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String piece : pieces) {
            if (current.isEmpty()) {
                current.append(piece);
                continue;
            }
            // 判断合并后（加上 "\n\n" 共 2 个字符）是否仍在目标长度 size 以内
            if (current.length() + 2 + piece.length() <= size) {
                current.append("\n\n").append(piece);
                continue;
            }
            // 超过上限则先将当前累积的分段封箱，再用新碎片开启下一个分段
            packed.add(current.toString());
            current.setLength(0);
            current.append(piece);
        }
        if (!current.isEmpty()) {
            packed.add(current.toString());
        }
        return packed;
    }

    /**
     * 按字面分隔符精确切分文本（不使用正则匹配）。
     * 分隔符本身不会保留在结果分段里，切出后首尾空白会被裁掉，空分段自动丢弃。
     *
     * @param text      待切分的原始文本
     * @param delimiter 字面分隔符（不能为空）
     * @return 切分后的非空分段列表
     */
    public static List<String> splitByDelimiter(String text, String delimiter) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if (delimiter == null || delimiter.isEmpty()) {
            throw new IllegalArgumentException("分隔符不能为空");
        }
        // 统一正文与分隔符中的换行符格式，确保跨平台换行匹配一致
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').strip();
        String needle = delimiter.replace("\r\n", "\n").replace('\r', '\n');
        List<String> parts = new ArrayList<>();
        int start = 0;
        int found = normalized.indexOf(needle, start);
        // 循环查找字面分隔符位置并截取中间片段
        while (found >= 0) {
            addPiece(parts, normalized.substring(start, found));
            start = found + needle.length();
            found = normalized.indexOf(needle, start);
        }
        // 收尾：加入最后一个分隔符之后的剩余文本
        addPiece(parts, normalized.substring(start));
        return parts;
    }

    /**
     * 将用户在前端或接口中输入的字面转义序列（{@code \n}、{@code \t}、{@code \r}、{@code \\}）还原为真实的换行符、制表符和反斜杠。
     * 未识别的 {@code \x} 序列保持原样输出。
     *
     * @param raw 含转义字符的原始字符串
     * @return 反转义后的实际字符串
     */
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

    /**
     * 去除片段首尾空白，若非空则追加到结果列表中。
     */
    private static void addPiece(List<String> parts, String piece) {
        String text = piece.strip();
        if (!text.isEmpty()) {
            parts.add(text);
        }
    }
}
