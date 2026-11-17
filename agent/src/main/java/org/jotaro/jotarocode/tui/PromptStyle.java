package org.jotaro.jotarocode.tui;

/**
 * TUI 终端 ANSI 颜色、边框与状态条样式工具类。
 * <p>
 * 负责绘制底部输入框的上下边框（{@code ╭─ jotaro ─...} / {@code ╭─ steer ─...} 与 {@code ╰─ model · hint ─...}）、
 * 光标行提示符 {@code ❯ }、输入框上方的实时半行与状态行，以及助手消息圆点、工具名染色和超长行尾部截断。
 * 严格使用单列宽度的 Unicode 符号，防止终端计算光标列偏移时出现错位。
 */
final class PromptStyle {

    private PromptStyle() {
    }

    /**
     * 生成输入行左侧提示符 {@code ❯ }（运行中与空闲时使用不同暖色调）。
     */
    static String left(boolean running) {
        String accent = running ? "178" : "172";
        return color(accent) + "❯" + reset() + " ";
    }

    /** 助手每段回复或子代理块开头的橙色圆点标记 {@code ● }。 */
    static String mark() {
        return color("172") + "●" + reset() + " ";
    }

    /** 为工具调用摘要文本添加蓝青色（ANSI 256 色 74）样式。 */
    static String tool(String text) {
        return color("74") + text + reset();
    }

    /** 为次要信息（如工具结果预览、提示语）添加暗灰色样式。 */
    static String dim(String text) {
        return color("245") + text + reset();
    }

    /** 生成右侧模型名称暗灰样式字符串。 */
    static String right(String model) {
        if (model == null || model.isBlank()) {
            return null;
        }
        return color("246") + model + reset();
    }

    /**
     * 组装输入框上边框与输入行提示符：
     * 运行中在顶框线上方额外拼入一行正在生成的实时半行（{@code livePlain}）或当前状态（{@code status}）；
     * 空闲时只保留顶框线与输入提示符。
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

    /**
     * 生成输入框下边框（包含当前模型名与操作快捷键提示）。
     */
    static String footer(String model, boolean running, int columns) {
        String hint = running ? "继续输入会插入这一轮 · ctrl-c 停止" : "enter 发送 · / 命令";
        String body = model == null || model.isBlank() ? hint : model + " · " + hint;
        return ruleEdge("╰─ ", body, columns);
    }

    /** 生成输入框上边框（带 {@code jotaro} 或 {@code steer} 标题）。 */
    private static String rule(String name, int columns) {
        return ruleEdge("╭─ ", name, columns);
    }

    /**
     * 生成占满终端列宽的水平框线。
     */
    private static String ruleEdge(String edge, String label, int columns) {
        String head = edge + label + " ";
        int rest = Math.max(0, (columns <= 4 ? 72 : columns) - columnsOf(head));
        return color("240") + head + "─".repeat(rest) + reset();
    }

    /** 为运行中状态文本添加暗灰色样式。 */
    static String status(String text) {
        return color("245") + text + reset();
    }

    /**
     * 按终端可用列宽截取实时半行的末尾部分（超出时在前面加 {@code …}），
     * 确保输入框上方的实时预览始终保持在单行内，不会因自动折行撑坏输入框布局。
     */
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
            // 非 ASCII 字符（如中文）按 2 列宽度估算
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

    /**
     * 估算字符串在终端中占用的显示列宽（ASCII 占 1 列，非 ASCII 宽字符按 2 列计）。
     */
    private static int columnsOf(String text) {
        int cols = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            cols += codePoint > 127 ? 2 : 1;
            index += Character.charCount(codePoint);
        }
        return cols;
    }

    /** 为模型思考文本添加暗灰色样式。 */
    static String thinking(String text) {
        return color("245") + text + reset();
    }

    /** 生成 TUI 启动时的欢迎横幅。 */
    static String banner() {
        return color("172") + "◆" + reset()
                + " \u001b[1mJotaro\u001b[22m "
                + color("246") + "已启动 · /help 查看命令" + reset();
    }

    /** 构造 256 色前景色 ANSI 转义序列。 */
    private static String color(String code) {
        return "\u001b[38;5;" + code + "m";
    }

    /** 构造恢复默认前景色 ANSI 转义序列。 */
    private static String reset() {
        return "\u001b[39m";
    }
}
