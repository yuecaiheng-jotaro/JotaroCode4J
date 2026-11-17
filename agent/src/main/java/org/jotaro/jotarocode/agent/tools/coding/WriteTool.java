package org.jotaro.jotarocode.agent.tools.coding;

import org.jotaro.jotarocode.agent.tools.ToolContributor;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件写入工具（{@code write}）。
 *
 * <p>用于创建新文件或整文件覆盖已有文件（UTF-8 编码）。
 * 若目标文件的父目录不存在会自动递归创建；与 {@link EditTool} 共用 {@link FileMutationLock} 分段锁以确保同一文件的并发写安全。
 * 属于需要用户交互确认的高风险写操作工具。
 */
@Component
public class WriteTool implements ToolContributor {

    @Override
    public String displayLabel() {
        return "Write";
    }

    /**
     * 写文件会创建或覆盖磁盘内容，执行前需要用户在终端确认。
     */
    @Override
    public boolean requiresConfirmation() {
        return true;
    }

    /**
     * 将指定文本内容以 UTF-8 编码写入目标文件（自动创建缺失的父目录，若文件已存在则直接覆盖）。
     *
     * @param path    目标文件路径（相对路径或绝对路径）
     * @param content 待写入的完整文本内容（若为 {@code null} 则写入空字符串）
     * @return 写入成功的提示信息
     */
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
        // 与 EditTool 共享路径分段锁，避免并发写同一文件
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
