package org.jotaro.jotarocode.tui;

/**
 * 终端样式。输入区是底部一框，光标行只有一个窄的 {@code ❯}。
 * 运行时，正在生成的文字或当前动作显示在框的上方。
 * 只用一列宽的符号，避免光标比实际字符多偏一格。
 */
final class PromptStyle {

    private PromptStyle() {
    }

    static String left(boolean running) {
        String accent = running ? "178" : "172";
        return color(accent) + "❯" + reset() + " ";
    }

    /** 助手每段开头的圆点。 */
    static String mark() {
        return color("172") + "●" + reset() + " ";
    }

    static String tool(String text) {
        return color("74") + text + reset();
    }

    static String dim(String text) {
        return color("245") + text + reset();
    }

    static String user(String text) {
        return "\u001b[1m❯ " + text + "\u001b[22m";
    }

    static String right(String model) {
        if (model == null || model.isBlank()) {
            return null;
        }
        return color("246") + model + reset();
    }

    /**
     * 输入框。运行中先放正在生成的半行或状态，再画上框线；空闲时只留框。
     */
    static String frame(boolean running, String livePlain, boolean thinking, String status, int columns) {
        String rule = rule(running ? "steer" : "jotaro", columns);
        String input = left(running);
        if (!running) {
            return rule + "\n" + input;
        }
        String band = livePlain == null || livePlain.isEmpty()
                ? status(status == null || status.isBlank() ? "正在回复" : status)
                : (thinking ? thinking(livePlain) : livePlain);
        return band + "\n" + rule + "\n" + input;
    }

    static String footer(String model, boolean running, int columns) {
        String hint = running ? "继续输入会插入这一轮 · ctrl-c 停止" : "enter 发送 · / 命令";
        String body = model == null || model.isBlank() ? hint : model + " · " + hint;
        return ruleEdge("╰─ ", body, columns);
    }

    private static String rule(String name, int columns) {
        return ruleEdge("╭─ ", name, columns);
    }

    private static String ruleEdge(String edge, String label, int columns) {
        String head = edge + label + " ";
        int rest = Math.max(0, (columns <= 4 ? 72 : columns) - columnsOf(head));
        return color("240") + head + "─".repeat(rest) + reset();
    }

    static String status(String text) {
        return color("245") + text + reset();
    }

    /** 只保留当前行末尾，让新出来的字留在输入框上方这一行里。 */
    static String tail(String text, int columns) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int width = columns <= 4 ? 72 : columns - 1;
        if (columnsOf(text) <= width) {
            return text;
        }
        int budget = width - 1;
        int cols = 0;
        int start = text.length();
        for (int index = text.length(); index > 0; ) {
            int codePoint = text.codePointBefore(index);
            int taken = codePoint > 127 ? 2 : 1;
            if (cols + taken > budget) {
                break;
            }
            cols += taken;
            index -= Character.charCount(codePoint);
            start = index;
        }
        return "…" + text.substring(start);
    }

    private static int columnsOf(String text) {
        int cols = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            cols += codePoint > 127 ? 2 : 1;
            index += Character.charCount(codePoint);
        }
        return cols;
    }

    static String thinking(String text) {
        return color("245") + text + reset();
    }

    static String banner() {
        return color("172") + "◆" + reset()
                + " \u001b[1mJotaro\u001b[22m "
                + color("246") + "已启动 · /help 查看命令" + reset();
    }

    private static String color(String code) {
        return "\u001b[38;5;" + code + "m";
    }

    private static String reset() {
        return "\u001b[39m";
    }
}
