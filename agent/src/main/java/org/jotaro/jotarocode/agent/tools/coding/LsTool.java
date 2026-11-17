package org.jotaro.jotarocode.agent.tools.coding;

import org.jotaro.jotarocode.agent.tools.ToolContributor;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 目录列表查看工具（{@code ls}）。
 *
 * <p>列出指定目录下的直接子项（包含以 {@code .} 开头的隐藏文件），按名称不区分大小写排序。
 * 子目录名称末尾附加 {@code /} 后缀；默认最多返回 500 个条目或 50KB 输出。
 */
@Component
public class LsTool implements ToolContributor {

    /** 默认最大返回的目录项数量。 */
    private static final int DEFAULT_LIMIT = 500;

    @Override
    public String displayLabel() {
        return "List";
    }

    /**
     * 列出指定目录的直接子文件和子目录。
     *
     * @param path  目标目录路径（可选，默认为当前工作目录）
     * @param limit 最大返回条目数（可选，默认 500）
     * @return 按字母排序的目录内容列表，空目录返回 {@code "(empty directory)"}
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "ls",
            description = "List directory contents. Returns entries sorted alphabetically, with '/' suffix for directories. "
                    + "Includes dotfiles. Output is truncated to 500 entries or 50KB (whichever is hit first)."
    )
    public String ls(
            @ToolParam(description = "Directory to list (default: current directory)", required = false) String path,
            @ToolParam(description = "Maximum number of entries to return (default: 500)", required = false) Integer limit
    ) {
        Path directory = ToolPaths.resolve(path == null || path.isBlank() ? "." : path);
        if (!Files.exists(directory)) {
            throw new IllegalArgumentException("Path not found: " + directory);
        }
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Not a directory: " + directory);
        }
        int effectiveLimit = Math.max(1, limit == null ? DEFAULT_LIMIT : limit);
        List<String> names;
        try (Stream<Path> stream = Files.list(directory)) {
            names = stream.map(entry -> entry.getFileName().toString()).sorted(Comparator.comparing(String::toLowerCase)).toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read directory: " + exception.getMessage(), exception);
        }
        List<String> results = new ArrayList<>();
        boolean entryLimitReached = false;
        for (String name : names) {
            if (results.size() >= effectiveLimit) {
                entryLimitReached = true;
                break;
            }
            Path child = directory.resolve(name);
            try {
                results.add(Files.isDirectory(child) ? name + "/" : name);
            } catch (UncheckedIOException | SecurityException exception) {
                // 无法读取属性的条目跳过，和原生 ls 一样继续看后面的名字。
            }
        }
        if (results.isEmpty()) {
            return "(empty directory)";
        }
        ToolOutput.Truncation truncation = ToolOutput.head(String.join("\n", results), Integer.MAX_VALUE, ToolOutput.MAX_BYTES);
        String output = truncation.content();
        List<String> notices = new ArrayList<>();
        if (entryLimitReached) {
            notices.add(effectiveLimit + " entries limit reached. Use limit=" + (effectiveLimit * 2) + " for more");
        }
        if (truncation.truncated()) {
            notices.add(ToolOutput.formatSize(ToolOutput.MAX_BYTES) + " limit reached");
        }
        if (!notices.isEmpty()) {
            output += "\n\n[" + String.join(". ", notices) + "]";
        }
        return output;
    }
}
