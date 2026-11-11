package org.jotaro.jotarocode.agent.tools.coding;

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

/** 列目录。目录名以 {@code /} 结尾，包含点文件，默认最多 500 条，并跳过 gitignore 命中的项。 */
@Component
public class LsTool {

    private static final int DEFAULT_LIMIT = 500;

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
