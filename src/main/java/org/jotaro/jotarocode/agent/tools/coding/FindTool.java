package org.jotaro.jotarocode.agent.tools.coding;

import org.jotaro.jotarocode.agent.tools.ToolContributor;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 按 glob 找文件路径。默认最多 1000 条，遵守 .gitignore，不进入 .git。 */
@Component
public class FindTool implements ToolContributor {

    private static final int DEFAULT_LIMIT = 1000;

    @org.springframework.ai.tool.annotation.Tool(
            name = "find",
            description = "Search for files by glob pattern. Returns matching file paths relative to the search directory. "
                    + "Respects .gitignore. Output is truncated to 1000 results or 50KB (whichever is hit first)."
    )
    public String find(
            @ToolParam(description = "Glob pattern to match files, e.g. '*.java', '**/*.json', or 'src/**/*.spec.ts'") String pattern,
            @ToolParam(description = "Directory to search in (default: current directory)", required = false) String path,
            @ToolParam(description = "Maximum number of results (default: 1000)", required = false) Integer limit
    ) {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("pattern must not be empty");
        }
        Path search = ToolPaths.resolve(path == null || path.isBlank() ? "." : path);
        if (!Files.exists(search)) {
            throw new IllegalArgumentException("Path not found: " + search);
        }
        if (!Files.isDirectory(search)) {
            throw new IllegalArgumentException("Not a directory: " + search);
        }
        int effectiveLimit = Math.max(1, limit == null ? DEFAULT_LIMIT : limit);
        List<String> results = new ArrayList<>();
        GitIgnore ignore = new GitIgnore(search);
        try {
            Files.walkFileTree(search, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (Files.isSymbolicLink(dir) && !dir.equals(search)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!dir.equals(search) && ignore.ignored(dir, true)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!dir.equals(search)) {
                        add(search.relativize(dir).toString().replace('\\', '/') + "/");
                    }
                    return results.size() > effectiveLimit ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (Files.isSymbolicLink(file) || ignore.ignored(file, false)) {
                        return FileVisitResult.CONTINUE;
                    }
                    add(search.relativize(file).toString().replace('\\', '/'));
                    return results.size() > effectiveLimit ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                private void add(String relative) {
                    if (results.size() > effectiveLimit || !GlobPatterns.matches(pattern, relative.endsWith("/")
                            ? relative.substring(0, relative.length() - 1)
                            : relative)) {
                        return;
                    }
                    results.add(relative);
                }
            });
        } catch (IOException exception) {
            throw new IllegalStateException("Search failed: " + exception.getMessage(), exception);
        }
        if (results.isEmpty()) {
            return "No files found matching pattern";
        }
        boolean resultLimitReached = results.size() > effectiveLimit;
        List<String> shown = new ArrayList<>(resultLimitReached ? results.subList(0, effectiveLimit) : results);
        shown.sort(String.CASE_INSENSITIVE_ORDER);
        ToolOutput.Truncation truncation = ToolOutput.head(String.join("\n", shown), Integer.MAX_VALUE, ToolOutput.MAX_BYTES);
        String output = truncation.content();
        List<String> notices = new ArrayList<>();
        if (resultLimitReached) {
            notices.add(effectiveLimit + " results limit reached");
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
