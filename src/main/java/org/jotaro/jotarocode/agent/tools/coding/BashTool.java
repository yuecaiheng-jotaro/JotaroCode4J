package org.jotaro.jotarocode.agent.tools.coding;

import org.jotaro.jotarocode.agent.tools.ToolContributor;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 在工作目录执行 bash。默认 120 秒超时，非零退出码和超时都会抛异常，循环把它记成工具错误。 */
@Component
public class BashTool implements ToolContributor {

    @org.springframework.ai.tool.annotation.Tool(
            name = "bash",
            description = "Execute a bash command in the current working directory. Returns stdout and stderr. "
                    + "Output is truncated to the last 2000 lines or 50KB (whichever is hit first). "
                    + "If truncated, full output is saved to a temp file. Timeout is in seconds and defaults to 120."
    )
    public String bash(
            @ToolParam(description = "Shell command to execute") String command,
            @ToolParam(description = "Timeout in seconds (optional, default 120)", required = false) Integer timeout
    ) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        Path bash = Path.of("/bin/bash");
        String executable = Files.isExecutable(bash) ? bash.toString() : "bash";
        return ShellRunner.run(List.of(executable, "-c", command), ToolPaths.cwd(), timeout, "pi-bash");
    }
}
