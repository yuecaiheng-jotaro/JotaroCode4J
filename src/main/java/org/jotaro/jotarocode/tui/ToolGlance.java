package org.jotaro.jotarocode.tui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 把工具调用收成 Claude 那种一行：{@code Read src/App.java}，而不是整段 JSON。
 */
final class ToolGlance {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ToolGlance() {
    }

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

    static String label(String name) {
        if (name == null || name.isBlank()) {
            return "Tool";
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
            default -> name;
        };
    }

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
