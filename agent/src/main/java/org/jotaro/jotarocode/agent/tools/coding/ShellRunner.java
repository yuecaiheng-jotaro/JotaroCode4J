package org.jotaro.jotarocode.agent.tools.coding;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 子进程命令执行与输出截断运行器（供 {@link BashTool} 和 {@link PowerShellTool} 共用）。
 *
 * <p>核心机制与边界处理：
 * <ul>
 *   <li><b>防管道死锁</b>：将子进程的 {@code stdout} 与 {@code stderr} 合并后直接重定向到磁盘临时文件，避免因 OS 管道缓冲区写满导致子进程挂起。</li>
 *   <li><b>两级尾部截断与临时文件保留</b>：先从临时日志文件末尾读取最多 {@link #MAX_CAPTURE_BYTES}（1MB）数据，再经 {@link ToolOutput#tail(String)} 截取最后 2000 行或 50KB 返回给模型；
 *       一旦发生截断，保留该临时文件并在返回结果中附上完整输出文件的绝对路径，未截断则在 {@code finally} 中自动清理临时文件。</li>
 *   <li><b>超时与进程树清理</b>：超时或当前线程被中断时，递归强制销毁子进程及其所有后代进程（{@code descendants}），并抛出带已有输出的异常。</li>
 * </ul>
 */
final class ShellRunner {

    /** 默认命令执行超时时间（120 秒）。 */
    static final int DEFAULT_TIMEOUT_SECONDS = 120;
    /** 允许设置的最大超时秒数上限。 */
    private static final int MAX_TIMEOUT_SECONDS = Integer.MAX_VALUE / 1000;
    /** 从输出日志文件尾部最多读取到内存中的字节数（1MB）。 */
    private static final int MAX_CAPTURE_BYTES = 1024 * 1024;

    private ShellRunner() {
    }

    /**
     * 启动外部进程执行命令，等待完成或超时，并返回截断后的输出文本。
     *
     * @param argv           可执行程序及其参数列表
     * @param cwd            子进程工作目录
     * @param timeoutSeconds 超时时间（秒），为 {@code null} 时使用默认 120 秒
     * @param tempPrefix     输出临时文件名前缀
     * @return 命令输出文本（若无输出则返回 {@code "(no output)"}）
     */
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
            // 若输出超过 1MB 或触发了 2000 行 / 50KB 尾部截断，保留临时日志文件供后续按需查阅
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

    /** 创建用于承接子进程标准输出与错误输出的临时日志文件。 */
    private static Path createTemp(String tempPrefix) {
        try {
            return Files.createTempFile(tempPrefix, ".log");
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create output file: " + exception.getMessage(), exception);
        }
    }

    /** 启动子进程并将标准错误合并重定向到临时文件。 */
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

    /** 等待子进程在指定秒数内退出；若线程被中断则强杀进程树并抛出中断异常。 */
    private static boolean wait(Process process, int timeout) {
        try {
            return process.waitFor(timeout, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            kill(process);
            throw new IllegalStateException("Command aborted");
        }
    }

    /** 递归强杀子进程的所有后代进程及其自身。 */
    private static void kill(Process process) {
        process.descendants().forEach(handle -> handle.destroyForcibly());
        process.destroyForcibly();
    }

    /**
     * 使用 {@link RandomAccessFile} 从临时文件末尾读取最多 {@link #MAX_CAPTURE_BYTES}（1MB）字节。
     */
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

    /** 将已捕获的命令输出与状态/错误描述拼接在一起。 */
    private static String status(String text, String message) {
        if (text == null || text.isEmpty()) {
            return message;
        }
        return text + "\n\n" + message;
    }
}
