package org.jotaro.jotarocode.mcp;

import java.util.Set;

/** 把 MCP 服务名和工具名收成模型能调用的一个名字，并躲开内置工具。 */
public final class McpToolNames {

    static final int LIMIT = 64;
    private static final Set<String> BUILTIN = Set.of(
            "read", "write", "edit", "bash", "powershell", "ls", "grep", "find",
            "currentTime", "terminate", "subagent", "rag"
    );

    private McpToolNames() {
    }

    public static boolean builtin(String name) {
        return BUILTIN.contains(name);
    }

    public static String join(String server, String tool) {
        return fit(token(server, "mcp") + "_" + token(tool, "tool"));
    }

    static String fit(String value) {
        String text = value == null ? "" : value;
        if (text.length() > LIMIT) {
            text = text.substring(text.length() - LIMIT);
        }
        if (text.isEmpty() || !Character.isLetter(text.charAt(0))) {
            text = "m" + text;
        }
        if (text.length() > LIMIT) {
            text = text.substring(0, LIMIT);
        }
        return text;
    }

    static String token(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < raw.length(); index++) {
            char character = raw.charAt(index);
            builder.append(Character.isLetterOrDigit(character) || character == '_' ? character : '_');
        }
        String token = trim(builder.toString(), '_');
        if (token.isEmpty()) {
            return fallback;
        }
        if (!Character.isLetter(token.charAt(0))) {
            token = "m_" + token;
        }
        return token;
    }

    private static String trim(String value, char edge) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == edge) {
            start++;
        }
        while (end > start && value.charAt(end - 1) == edge) {
            end--;
        }
        return value.substring(start, end);
    }
}
