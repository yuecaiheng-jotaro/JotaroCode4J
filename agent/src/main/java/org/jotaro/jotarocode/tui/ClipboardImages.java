package org.jotaro.jotarocode.tui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 系统剪贴板图片与文本读取工具。
 * <p>
 * 跨平台（macOS / Linux）读取系统剪贴板：
 * <ul>
 *   <li>图片优先于文字：当剪贴板中同时存在图片或以图片为主（如截图）时，将其导出为临时图片文件并返回路径。</li>
 *   <li>macOS 下终端的 {@code Cmd+V} 仅触发括号粘贴送入文本，粘贴剪贴板图片需使用 {@code Ctrl+V} 由本类调用 {@code osascript} 提取。</li>
 *   <li>Linux 下依次尝试 Wayland（{@code wl-paste}）与 X11（{@code xclip}）。</li>
 * </ul>
 */
final class ClipboardImages {

    private ClipboardImages() {
    }

    /**
     * 尝试从系统剪贴板读取图片并保存为临时文件。
     *
     * @return 成功导出图片时返回临时文件路径，否则返回 {@link Optional#empty()}
     */
    static Optional<Path> image() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("mac")) {
                return macImage();
            }
            if (os.contains("linux")) {
                return linuxImage();
            }
        } catch (Exception exception) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * 从系统剪贴板读取纯文本内容。
     *
     * @return 剪贴板文本，读取失败或为空时返回空字符串
     */
    static String text() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("mac")) {
                return run(List.of("pbpaste"), 1500);
            }
            if (os.contains("linux")) {
                String wayland = run(List.of("wl-paste", "--no-newline"), 1500);
                if (!wayland.isBlank()) {
                    return wayland;
                }
                return run(List.of("xclip", "-selection", "clipboard", "-o"), 1500);
            }
        } catch (Exception exception) {
            return "";
        }
        return "";
    }

    /**
     * 根据 macOS {@code clipboard info} 输出判断剪贴板是否以图片为主。
     * <p>
     * 当剪贴板包含图片格式且图片类型出现在文本类型之前（或不含文本类型）时，按图片粘贴处理。
     *
     * @param info {@code osascript -e 'clipboard info as string'} 的输出
     * @return 若图片优先则返回 {@code true}
     */
    static boolean imageIsPrimary(String info) {
        if (info == null || info.isBlank()) {
            return false;
        }
        int imageAt = earliest(info, "PNGf", "JPEG picture", "TIFF picture", "GIFf");
        if (imageAt < 0) {
            return false;
        }
        int textAt = earliest(info, "utf8", "Unicode text");
        return textAt < 0 || imageAt < textAt;
    }

    /**
     * 在 macOS 上通过 {@code osascript} 将剪贴板中的 PNG 或 JPEG 图片写出到临时文件。
     */
    private static Optional<Path> macImage() throws Exception {
        String info = run(List.of("osascript", "-e", "clipboard info as string"), 1500);
        if (!imageIsPrimary(info)) {
            return Optional.empty();
        }
        Path file = Files.createTempFile("jotaro-paste-", ".png");
        String script = """
                set outFile to POSIX file "%s"
                try
                  set imageData to the clipboard as «class PNGf»
                  set kind to "png"
                on error
                  try
                    set imageData to the clipboard as JPEG picture
                    set kind to "jpeg"
                  on error
                    return "none"
                  end try
                end try
                set fileRef to open for access outFile with write permission
                set eof fileRef to 0
                write imageData to fileRef
                close access fileRef
                return kind
                """.formatted(file.toAbsolutePath());
        String result = run(List.of("osascript", "-e", script), 3000);
        if (Files.size(file) == 0 || (!"png".equals(result) && !"jpeg".equals(result))) {
            Files.deleteIfExists(file);
            return Optional.empty();
        }
        // 若剪贴板实际导出的是 JPEG，则将临时文件后缀改为 .jpg 以便正确识别 MIME 类型
        if ("jpeg".equals(result)) {
            Path jpeg = file.resolveSibling(file.getFileName().toString().replace(".png", ".jpg"));
            Files.move(file, jpeg);
            return Optional.of(jpeg);
        }
        return Optional.of(file);
    }

    /**
     * 在 Linux 上通过 {@code wl-paste}（Wayland）或 {@code xclip}（X11）导出剪贴板 PNG 图片。
     */
    private static Optional<Path> linuxImage() throws Exception {
        Path file = Files.createTempFile("jotaro-paste-", ".png");
        int wayland = exec(List.of("wl-paste", "--type", "image/png"), file, 1500);
        if (wayland == 0 && Files.size(file) > 0) {
            return Optional.of(file);
        }
        int x11 = exec(List.of("xclip", "-selection", "clipboard", "-t", "image/png", "-o"), file, 1500);
        if (x11 == 0 && Files.size(file) > 0) {
            return Optional.of(file);
        }
        Files.deleteIfExists(file);
        return Optional.empty();
    }

    /**
     * 查找多个候选标记在字符串中最早出现的位置下标。
     */
    private static int earliest(String info, String... tokens) {
        int found = -1;
        for (String token : tokens) {
            int at = info.indexOf(token);
            if (at >= 0 && (found < 0 || at < found)) {
                found = at;
            }
        }
        return found;
    }

    /**
     * 执行外部命令并在超时时间内读取标准输出文本。
     */
    private static String run(List<String> command, long timeoutMs) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (!finished) {
            process.destroyForcibly();
            return "";
        }
        return output;
    }

    /**
     * 执行外部命令并将标准输出直接重定向写入指定文件。
     */
    private static int exec(List<String> command, Path output, long timeoutMs) throws Exception {
        Process process = new ProcessBuilder(command).redirectOutput(output.toFile()).redirectErrorStream(true).start();
        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            return -1;
        }
        return process.exitValue();
    }
}
