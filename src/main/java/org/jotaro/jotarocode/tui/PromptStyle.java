package org.jotaro.jotarocode.tui;

/**
 * 终端提示符。空闲是铜色 {@code ◆ jotaro ❯}，运行中改成金色 {@code ◆ steer ❯}，模型名放在行尾。
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
