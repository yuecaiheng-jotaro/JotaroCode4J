package org.jotaro.jotarocode.tui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具调用与结果单行摘要格式化器。
 * <p>
 * 将冗长的工具调用 JSON 入参提炼成类似 Claude Code 的紧凑单行摘要（例如 {@code Read src/App.java}、{@code Bash git status}），
 * 并将多行工具输出折叠截断为不超过 72 字符的单行预览，保持终端输出清爽易读。
 */
public final class ToolGlance {

    /** 用于解析工具入参 JSON 的 ObjectMapper。 */
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 运行期动态注册的自定义工具显示名映射表（key 为工具名，value 为展示标签）。 */
    private static final Map<String, String> DYNAMIC_LABELS = new ConcurrentHashMap<>();

    private ToolGlance() {
    }

    /**
     * 为外部或自定义工具注册终端展示名称（如将某个长工具名映射为简短标签）。
     *
     * @param toolName 原始工具名称
     * @param label    终端上展示的简短标签
     */
    public static void registerLabel(String toolName, String label) {
        if (toolName != null && !toolName.isBlank() && label != null && !label.isBlank()) {
            DYNAMIC_LABELS.put(toolName, label.trim());
        }
    }

    /**
     * 将工具名称与 JSON 入参合成为单行调用摘要（提取关键字段如 {@code command}、{@code path}、{@code pattern} 等）。
     *
     * @param name      工具名称
     * @param arguments 工具调用 JSON 参数字符串
     * @return 紧凑的单行调用描述
     */
    static String call(String name, String arguments) {
        String label = label(name);
        String extra = first(arguments, "command", "path", "file_path", "pattern", "query", "glob");
        if (extra.isEmpty() && "subagent".equals(name)) {
            String agent = first(arguments, "agent");
            String task = first(arguments, "task");
            extra = agent.isEmpty() ? task : task.isEmpty() ? agent : agent + " · " + task;
        }
        extra = preview(extra);
        return extra.isEmpty() ? label : label + " " + extra;
    }

    /**
     * 将多行或超长文本压缩为不超过 72 个字符的单行预览（超出时末尾加 {@code …}）。
     *
     * @param text 原始文本
     * @return 单行预览字符串
     */
    static String preview(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String single = text.replace('\r', ' ').replace('\n', ' ').strip().replaceAll(" +", " ");
        if (single.length() <= 72) {
            return single;
        }
        return single.substring(0, 71) + "…";
    }

    /**
     * 获取工具在终端里的友好显示标签（优先查找动态注册表，其次匹配内置工具名）。
     *
     * @param name 工具原始名称
     * @return 显示标签
     */
    static String label(String name) {
        if (name == null || name.isBlank()) {
            return "Tool";
        }
        String registered = DYNAMIC_LABELS.get(name);
        if (registered != null) {
            return registered;
        }
        return switch (name) {
            case "read" -> "Read";
            case "write" -> "Write";
            case "edit" -> "Edit";
            case "bash" -> "Bash";
            case "powershell" -> "PowerShell";
            case "ls" -> "List";
            case "grep" -> "Grep";
            case "find" -> "Find";
            case "currentTime" -> "Time";
            case "terminate" -> "Done";
            case "subagent" -> "Agent";
            case "rag" -> "Rag";
            case "webSearch" -> "Search";
            default -> name;
        };
    }

    /**
     * 从工具入参 JSON 中按给定字段顺序提取第一个非空字符串值。
     */
    private static String first(String arguments, String... fields) {
        JsonNode node = read(arguments);
        if (node == null) {
            return "";
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return "";
    }

    /**
     * 安全解析 JSON 字符串，解析失败时返回 {@code null}。
     */
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
}
