package org.jotaro.jotarocode.tui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 复杂工具执行前的问卷。只读工具直接放行。
 * {@code y} 允许这次，{@code a} 本次会话全部允许，{@code n} 拒绝。
 * 其他文字当作提示交回模型，不执行这次调用。
 */
final class ToolQuestionnaire {

    static final Set<String> ASK = Set.of("bash", "powershell", "write", "edit");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int PREVIEW_LIMIT = 400;

    private ToolQuestionnaire() {
    }

    static boolean needsAsk(String toolName) {
        return toolName != null && ASK.contains(toolName);
    }

    static List<String> prompt(String toolName, String arguments) {
        List<String> lines = new ArrayList<>();
        lines.add("╭ 允许执行 " + ToolGlance.label(toolName));
        lines.add("│ " + preview(toolName, arguments));
        lines.add("╰ y 允许这次 · a 全部允许 · n 拒绝 · 其他文字当作提示");
        return lines;
    }

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

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        return value.isTextual() ? value.asText() : value.toString();
    }

    private static int editCount(JsonNode node) {
        JsonNode edits = node.get("edits");
        return edits != null && edits.isArray() ? edits.size() : 0;
    }

    private static String shorten(String text) {
        String flat = text.replace('\n', ' ').trim();
        if (flat.length() <= PREVIEW_LIMIT) {
            return flat;
        }
        return flat.substring(0, PREVIEW_LIMIT) + "…";
    }

    record Parsed(Kind kind, String hint) {
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
