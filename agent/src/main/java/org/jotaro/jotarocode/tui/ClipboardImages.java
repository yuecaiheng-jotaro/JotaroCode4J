package org.jotaro.jotarocode.tui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 读系统剪贴板。图片优先于文字：截图的剪贴板里没有正文。
 * macOS 上终端的 Cmd+V 通常只送文字，图片用 Ctrl+V，由这里直接读剪贴板。
 */
final class ClipboardImages {

    private ClipboardImages() {
    }

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

    /** 剪贴板信息里图片类型排在文字前面时，这次粘贴按图片处理。 */
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
        if ("jpeg".equals(result)) {
            Path jpeg = file.resolveSibling(file.getFileName().toString().replace(".png", ".jpg"));
            Files.move(file, jpeg);
            return Optional.of(jpeg);
        }
        return Optional.of(file);
    }

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
