package com.kama.piagent.agent.tools;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class ReadFileTool implements Tool {

    private static final long MAX_BYTES = 64 * 1024;

    @Override
    public String getName() {
        return "readFile";
    }

    @Override
    public String getDescription() {
        return "读取工作目录内的文本文件";
    }

    @Override
    public ToolType getType() {
        return ToolType.OPTIONAL;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "readFile",
            description = "读取工作目录内的文本文件。path 是相对工作目录的路径，不能跳出工作目录。"
    )
    public String readFile(String path) {
        try {
            Path resolved = resolve(path);
            if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
                return "文件不存在: " + path;
            }
            if (Files.size(resolved) > MAX_BYTES) {
                return "文件过大，仅支持 64KB 以内的文本文件";
            }
            return Files.readString(resolved);
        } catch (IOException exception) {
            return "读取失败: " + exception.getMessage();
        } catch (IllegalArgumentException exception) {
            return exception.getMessage();
        }
    }

    private Path resolve(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        Path base = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path resolved = base.resolve(path).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("路径超出工作目录");
        }
        return resolved;
    }
}
