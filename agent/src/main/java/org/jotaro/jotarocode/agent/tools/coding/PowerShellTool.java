package org.jotaro.jotarocode.agent.tools.coding;

import org.jotaro.jotarocode.agent.tools.ToolContributor;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * PowerShell 命令执行工具（{@code powershell}）。
 *
 * <p>在系统环境变量 {@code PATH} 中按优先级查找 {@code pwsh}、{@code powershell} 或 {@code powershell.exe}。
 * 执行前会自动注入一行将控制台输出编码切换为 UTF-8 的前置指令，避免在 Windows 中文环境下出现标准输出乱码。
 * 属于需要用户交互确认的高风险工具。
 */
@Component
public class PowerShellTool implements ToolContributor {

    /** 强制将 PowerShell 控制台输出编码设为 UTF-8 的前置脚本片段。 */
    private static final String UTF8_PREFIX =
            "try { [Console]::OutputEncoding=[System.Text.Encoding]::UTF8 } catch {}\n";

    @Override
    public String displayLabel() {
        return "PowerShell";
    }

    /**
     * 执行外部命令属于高风险操作，需用户在终端确认。
     */
    @Override
    public boolean requiresConfirmation() {
        return true;
    }

    /**
     * 在当前工作目录下执行指定的 PowerShell 命令。
     *
     * @param command 待执行的 PowerShell 命令文本
     * @param timeout 超时时间（单位：秒，可选，默认 120 秒）
     * @return 标准输出与标准错误合并后的文本（超出上限时执行尾部截断并保留完整临时日志文件）
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "powershell",
            description = "Execute a PowerShell command in the current working directory. Returns stdout and stderr. "
                    + "Output is truncated to the last 2000 lines or 50KB (whichever is hit first). "
                    + "If truncated, full output is saved to a temp file. Timeout is in seconds and defaults to 120."
    )
    public String powershell(
            @ToolParam(description = "PowerShell command to execute") String command,
            @ToolParam(description = "Timeout in seconds (optional, default 120)", required = false) Integer timeout
    ) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        String executable = resolveExecutable();
        if (executable == null) {
            throw new IllegalStateException("PowerShell is not available. Install pwsh or powershell and make sure it is on PATH.");
        }
        String script = UTF8_PREFIX + command;
        return ShellRunner.run(
                List.of(executable, "-NoProfile", "-Command", script),
                ToolPaths.cwd(),
                timeout,
                "jotaro-powershell"
        );
    }

    /**
     * 从 {@code PATH} 环境变量中按 {@code pwsh -> powershell -> powershell.exe} 的顺序查找可执行文件。
     */
    private static String resolveExecutable() {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String name : List.of("pwsh", "powershell", "powershell.exe")) {
            for (String directory : path.split(java.io.File.pathSeparator)) {
                Path candidate = Path.of(directory).resolve(name);
                if (Files.isExecutable(candidate)) {
                    return candidate.toString();
                }
            }
        }
        return null;
    }
}
