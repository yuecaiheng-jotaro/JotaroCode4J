package org.jotaro.jotarocode.agent.tools.coding;

import org.jotaro.jotarocode.agent.tools.ToolContributor;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Bash 命令执行工具（{@code bash}）。
 *
 * <p>在当前工作目录（{@link ToolPaths#cwd()}）下通过 {@code /bin/bash -c}（或环境变量中的 {@code bash}）执行 Shell 命令。
 * 属于高风险操作，执行前需要用户交互确认（{@link #requiresConfirmation()} 返回 {@code true}）。
 * 默认超时时间为 120 秒；进程非零退出码或超时均会抛出异常，由 Agent 循环捕获并记为工具执行错误反馈给模型。
 */
@Component
public class BashTool implements ToolContributor {

    @Override
    public String displayLabel() {
        return "Bash";
    }

    /**
     * 执行 Shell 命令属于写/副作用操作，需用户在终端确认。
     */
    @Override
    public boolean requiresConfirmation() {
        return true;
    }

    /**
     * 在当前工作目录下执行指定的 Bash 命令。
     *
     * @param command 待执行的 Shell 命令字符串（不能为空）
     * @param timeout 超时时间（单位：秒，可选，默认 120 秒）
     * @return 标准输出与标准错误合并后的文本（超过 2000 行或 50KB 时保留尾部并保存完整输出到临时文件）
     */
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
        // 优先使用固定路径 /bin/bash，若不可执行则回退至 PATH 中的 bash
        Path bash = Path.of("/bin/bash");
        String executable = Files.isExecutable(bash) ? bash.toString() : "bash";
        return ShellRunner.run(List.of(executable, "-c", command), ToolPaths.cwd(), timeout, "jotaro-bash");
    }
}
