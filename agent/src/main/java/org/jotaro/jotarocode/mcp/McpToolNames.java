package org.jotaro.jotarocode.mcp;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 工具名称清洗、拼接与防冲突工具类。
 *
 * <p>大模型 Function Calling 对工具名有严格格式限制（仅允许字母、数字、下划线，首字符需为字母，最大长度 64 字符）。
 * 本类负责将 MCP 服务名（server）与工具名（tool）规范化拼接为合法工具标识符，并维护内置工具名集合以防止命名冲突。
 */
public final class McpToolNames {

    /** 大模型工具名称的最大字符长度上限。 */
    static final int LIMIT = 64;
    /** 已注册的内置工具名称集合，MCP 工具生成名称时必须避开这些保留名。 */
    private static final Set<String> BUILTIN = ConcurrentHashMap.newKeySet();

    static {
        BUILTIN.addAll(Set.of(
                "read", "write", "edit", "bash", "powershell", "ls", "grep", "find",
                "currentTime", "terminate", "subagent", "rag", "webSearch"
        ));
    }

    private McpToolNames() {
    }

    /**
     * 将自定义内置工具名注册到保留集合中，防止后续加载的 MCP 工具与其撞名。
     *
     * @param name 内置工具名称
     */
    public static void registerBuiltin(String name) {
        if (name != null && !name.isBlank()) {
            BUILTIN.add(name.trim());
        }
    }

    /**
     * 判断给定名称是否属于内置保留工具名。
     *
     * @param name 待检查的工具名
     * @return 若为内置工具名则返回 {@code true}
     */
    public static boolean builtin(String name) {
        return BUILTIN.contains(name);
    }

    /**
     * 将 MCP 服务名和工具原名清洗后用下划线拼接，并裁剪至最大长度限制以内。
     *
     * @param server MCP 服务名称
     * @param tool   MCP 工具原名
     * @return 符合大模型调用规范的组合工具名（如 {@code github_create_issue}）
     */
    public static String join(String server, String tool) {
        return fit(token(server, "mcp") + "_" + token(tool, "tool"));
    }

    /**
     * 将标识符长度收敛在 {@link #LIMIT}（64 字符）以内，并确保首字符为字母。
     *
     * <p>若超长则优先保留末尾部分（因为工具具体名称在末尾，区分度更高）；
     * 若裁剪后首字符不是字母，则补充前缀 {@code "m"} 并再次截断至上限。
     */
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

    /**
     * 清洗单个名称片段：将非字母、数字、下划线的字符统一替换为 {@code '_'}，
     * 去除首尾多余下划线，并确保以字母开头。
     *
     * @param raw      原始名称字符串
     * @param fallback 当原始名称为空或清洗后为空时使用的默认回退值
     * @return 清洗后的合法标识符片段
     */
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

    /**
     * 去除字符串首尾连续出现的指定字符。
     */
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
