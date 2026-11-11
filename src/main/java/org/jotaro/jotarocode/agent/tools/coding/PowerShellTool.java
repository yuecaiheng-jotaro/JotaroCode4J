package org.jotaro.jotarocode.agent.tools.coding;

import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 执行 PowerShell。优先 {@code pwsh}，没有再用 {@code powershell}。
 * 命令前加一行把控制台输出改成 UTF-8，避免中文乱码。
 */
@Component
public class PowerShellTool {

    private static final String UTF8_PREFIX =
            "try { [Console]::OutputEncoding=[System.Text.Encoding]::UTF8 } catch {}\n";

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
                "pi-powershell"
        );
    }

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
