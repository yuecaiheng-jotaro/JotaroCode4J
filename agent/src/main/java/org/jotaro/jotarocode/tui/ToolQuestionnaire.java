package org.jotaro.jotarocode.tui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 复杂工具执行前的确认问卷构建与回答解析器。
 * <p>
 * 规则说明：
 * <ul>
 *   <li>只读工具（如 {@code read}、{@code ls}、{@code grep}、{@code find} 等）直接放行；具有副作用的内置工具（{@code bash}、{@code powershell}、{@code write}、{@code edit}）及外部 MCP 工具在执行前需弹出问卷确认。</li>
 *   <li>回答 {@code y} / {@code yes} / {@code 是} / {@code 允许}：允许执行本次调用。</li>
 *   <li>回答 {@code a} / {@code all} / {@code 全部} / {@code 全部允许}：允许本次调用，且当前会话后续所有工具调用不再询问。</li>
 *   <li>回答 {@code n} / {@code no} / {@code 否} / {@code 拒绝}：拒绝本次调用。</li>
 *   <li>回答其他任意非空文字：不执行本次工具，并将该文字作为用户补充提示词交回给模型调整方案。</li>
 * </ul>
 */
public final class ToolQuestionnaire {

    /** 静态注册的需要执行前询问的工具名称集合。 */
    static final Set<String> ASK = ConcurrentHashMap.newKeySet();
    /** 启动时记住的外部扩展工具（如 MCP 工具）名称集合。 */
    private static volatile Set<String> extra = Set.of();
    /** 用于解析工具 JSON 参数以生成参数预览的 ObjectMapper。 */
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 问卷中展示工具命令或参数预览的最大字符长度。 */
    private static final int PREVIEW_LIMIT = 400;

    static {
        ASK.addAll(Set.of("bash", "powershell", "write", "edit"));
    }

    private ToolQuestionnaire() {
    }

    /**
     * 动态注册一个需要执行前弹出确认问卷的工具名称。
     *
     * @param toolName 工具名称
     */
    public static void registerAsk(String toolName) {
        if (toolName != null && !toolName.isBlank()) {
            ASK.add(toolName.trim());
        }
    }

    /**
     * 记录需要在执行前询问的额外工具集合（如启动时发现的所有 MCP 工具）。
     *
     * @param names 额外工具名称集合
     */
    static void remember(Collection<String> names) {
        extra = names == null || names.isEmpty() ? Set.of() : Set.copyOf(names);
    }

    /**
     * 判断指定工具在执行前是否需要向用户发起确认问卷。
     *
     * @param toolName 工具名称
     * @return 若需要询问则返回 {@code true}
     */
    static boolean needsAsk(String toolName) {
        return toolName != null && (ASK.contains(toolName) || extra.contains(toolName));
    }

    /**
     * 生成打印在终端输入框上方的工具确认问卷文本块。
     *
     * @param toolName  工具名称
     * @param arguments 工具 JSON 参数字符串
     * @return 多行问卷框文本
     */
    static List<String> prompt(String toolName, String arguments) {
        List<String> lines = new ArrayList<>();
        lines.add("╭ 允许执行 " + ToolGlance.label(toolName));
        lines.add("│ " + preview(toolName, arguments));
        lines.add("│ y 允许这次");
        lines.add("│ a 全部允许");
        lines.add("│ n 拒绝");
        lines.add("╰ 其他文字当作提示");
        return lines;
    }

    /**
     * 解析用户在终端输入的一行问卷回答。
     *
     * @param line 用户输入文本
     * @return 解析后的意图结果 {@link Parsed}
     */
    static Parsed parse(String line) {
        if (line == null || line.isBlank()) {
            return Parsed.again();
        }
        String token = line.trim();
        String lower = token.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "y", "yes", "是", "允许" -> Parsed.allowOnce();
            case "a", "all", "全部", "全部允许" -> Parsed.allowAll();
            case "n", "no", "否", "拒绝" -> Parsed.deny();
            default -> Parsed.hint(token);
        };
    }

    /**
     * 提取工具调用参数的可读预览（如命令全文、写入文件路径与字符数、编辑文件路径与替换处数）。
     */
    private static String preview(String toolName, String arguments) {
        JsonNode node = read(arguments);
        if (node == null) {
            return "参数: " + shorten(arguments == null ? "" : arguments);
        }
        return switch (toolName) {
            case "bash", "powershell" -> shorten(text(node, "command"));
            case "write" -> text(node, "path") + "（" + text(node, "content").length() + " 字符）";
            case "edit" -> text(node, "path") + "（" + editCount(node) + " 处替换）";
            default -> shorten(arguments);
        };
    }

    /** 安全解析 JSON 字符串。 */
    private static JsonNode read(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(arguments);
        } catch (Exception exception) {
            return null;
        }
    }

    /** 读取 JSON 节点下指定字段的文本值。 */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        return value.isTextual() ? value.asText() : value.toString();
    }

    /** 统计 {@code edit} 工具入参中 {@code edits} 数组的元素个数。 */
    private static int editCount(JsonNode node) {
        JsonNode edits = node.get("edits");
        return edits != null && edits.isArray() ? edits.size() : 0;
    }

    /** 将换行替换为空格并按 {@link #PREVIEW_LIMIT} 截断超长预览文本。 */
    private static String shorten(String text) {
        String flat = text.replace('\n', ' ').trim();
        if (flat.length() <= PREVIEW_LIMIT) {
            return flat;
        }
        return flat.substring(0, PREVIEW_LIMIT) + "…";
    }

    /**
     * 问卷回答解析结果。
     *
     * @param kind 回答分类
     * @param hint 当 {@code kind == HINT} 时携带的用户提示文本
     */
    record Parsed(Kind kind, String hint) {
        /** 问卷回答类型枚举：允许本次、全部允许、拒绝、作为提示词反馈、空输入重问。 */
        enum Kind { ALLOW_ONCE, ALLOW_ALL, DENY, HINT, AGAIN }

        static Parsed allowOnce() {
            return new Parsed(Kind.ALLOW_ONCE, null);
        }

        static Parsed allowAll() {
            return new Parsed(Kind.ALLOW_ALL, null);
        }

        static Parsed deny() {
            return new Parsed(Kind.DENY, null);
        }

        static Parsed hint(String hint) {
            return new Parsed(Kind.HINT, hint);
        }

        static Parsed again() {
            return new Parsed(Kind.AGAIN, null);
        }
    }
}
