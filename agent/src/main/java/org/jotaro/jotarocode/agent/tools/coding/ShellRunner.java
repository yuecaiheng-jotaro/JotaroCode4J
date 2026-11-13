package org.jotaro.jotarocode.agent.tools.coding;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 启动进程、等待超时、只把输出尾部交回模型。完整输出超过 1MB 时留在临时文件里，并在结果中写上路径。
 */
final class ShellRunner {

    static final int DEFAULT_TIMEOUT_SECONDS = 120;
    private static final int MAX_TIMEOUT_SECONDS = Integer.MAX_VALUE / 1000;
    private static final int MAX_CAPTURE_BYTES = 1024 * 1024;

    private ShellRunner() {
    }

    static String run(List<String> argv, Path cwd, Integer timeoutSeconds, String tempPrefix) {
        int timeout = timeoutSeconds == null ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
        if (timeout <= 0 || timeout > MAX_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException("Invalid timeout: must be a finite number of seconds");
        }
        if (!Files.isDirectory(cwd)) {
            throw new IllegalArgumentException("Working directory does not exist: " + cwd);
        }
        Path temp = createTemp(tempPrefix);
        boolean keep = false;
        try {
            Process process = start(argv, cwd, temp);
            boolean finished = wait(process, timeout);
            boolean fileTooBig = Files.size(temp) > MAX_CAPTURE_BYTES;
            ToolOutput.Truncation tail = ToolOutput.tail(readTail(temp));
            String text = tail.content();
            if (fileTooBig || tail.truncated()) {
                keep = true;
                text = (text.isEmpty() ? "" : text + "\n\n")
                        + "[Output truncated. Full output: " + temp.toAbsolutePath() + "]";
            }
            if (!finished) {
                kill(process);
                throw new IllegalStateException(status(text, "Command timed out after " + timeout + " seconds"));
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new IllegalStateException(status(text, "Command exited with code " + exitCode));
            }
            return text.isEmpty() ? "(no output)" : text;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read command output: " + exception.getMessage(), exception);
        } finally {
            if (!keep) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 临时输出留在磁盘上不影响命令结果。
                }
            }
        }
    }

    private static Path createTemp(String tempPrefix) {
        try {
            return Files.createTempFile(tempPrefix, ".log");
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create output file: " + exception.getMessage(), exception);
        }
    }

    private static Process start(List<String> argv, Path cwd, Path temp) {
        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.directory(cwd.toFile());
        // 合并输出并重定向到文件，避免等待退出时子进程因管道写满而卡住。
        builder.redirectErrorStream(true);
        builder.redirectOutput(temp.toFile());
        try {
            return builder.start();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to run command: " + exception.getMessage(), exception);
        }
    }

    private static boolean wait(Process process, int timeout) {
        try {
            return process.waitFor(timeout, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            kill(process);
            throw new IllegalStateException("Command aborted");
        }
    }

    private static void kill(Process process) {
        process.descendants().forEach(handle -> handle.destroyForcibly());
        process.destroyForcibly();
    }

    private static String readTail(Path file) throws IOException {
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(file.toFile(), "r")) {
            long size = randomAccessFile.length();
            int length = (int) Math.min(size, MAX_CAPTURE_BYTES);
            byte[] bytes = new byte[length];
            randomAccessFile.seek(size - length);
            randomAccessFile.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static String status(String text, String message) {
        if (text == null || text.isEmpty()) {
            return message;
        }
        return text + "\n\n" + message;
    }
}
