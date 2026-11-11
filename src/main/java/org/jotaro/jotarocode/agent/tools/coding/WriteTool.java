package org.jotaro.jotarocode.agent.tools.coding;

import org.jotaro.jotarocode.agent.tools.ToolContributor;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 创建或整文件覆盖。父目录不存在时会创建。和 edit 共用文件锁。 */
@Component
public class WriteTool implements ToolContributor {

    @org.springframework.ai.tool.annotation.Tool(
            name = "write",
            description = "Write content to a file. Creates the file if it doesn't exist, overwrites if it does. "
                    + "Automatically creates parent directories."
    )
    public String write(
            @ToolParam(description = "Path to the file to write (relative or absolute)") String path,
            @ToolParam(description = "Content to write to the file") String content
    ) {
        Path file = ToolPaths.resolve(path);
        String text = content == null ? "" : content;
        synchronized (FileMutationLock.of(file)) {
            try {
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(file, text, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to write " + path + ": " + exception.getMessage(), exception);
            }
        }
        return "Successfully wrote to " + path;
    }
}
