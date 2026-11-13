package org.jotaro.jotarocode.tui;

/**
 * 终端提示符。空闲是铜色 {@code ◆ jotaro ❯}，运行中改成金色 {@code ◆ steer ❯}，模型名放在行尾。
 * 运行时在输入框上方另起一行显示正在生成的文字，中间空一行，避免和输入贴在一起。
 * 只用一列宽的符号，避免光标比实际字符多偏一格。
 */
final class PromptStyle {

    private PromptStyle() {
    }

    static String left(boolean running) {
        String accent = running ? "178" : "172";
        String name = running ? "steer" : "jotaro";
        return color(accent) + "◆" + reset()
                + " \u001b[1m" + name + "\u001b[22m "
                + color(accent) + "❯" + reset()
                + " ";
    }

    static String right(String model) {
        if (model == null || model.isBlank()) {
            return null;
        }
        return color("246") + model + reset();
    }

    /**
     * 运行中的画面：上面是当前这行还没结束的输出，或一句状态；空一行之后才是输入提示符。
     * 空闲时只留输入提示符，上面的内容已经写进滚动区。
     */
    static String frame(boolean running, String livePlain, boolean thinking, String status) {
        if (!running) {
            return left(false);
        }
        String band = livePlain == null || livePlain.isEmpty()
                ? status(status == null || status.isBlank() ? "正在回复" : status)
                : (thinking ? thinking(livePlain) : livePlain);
        return band + "\n\n" + left(true);
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
