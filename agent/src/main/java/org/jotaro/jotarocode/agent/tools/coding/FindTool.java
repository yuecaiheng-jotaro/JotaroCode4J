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

/**
 * 按 Glob 模式查找文件路径的工具（{@code find}）。
 *
 * <p>主要特性与边界规则：
 * <ul>
 *   <li>从指定目录开始递归遍历文件树，返回相对于搜索根目录的路径（目录以 {@code /} 结尾）。</li>
 *   <li>自动跳过符号链接（Symlink）、{@code .git} 目录以及被 {@link GitIgnore} 规则忽略的文件和子目录。</li>
 *   <li>默认最多返回 1000 条结果，且总输出受 {@link ToolOutput#MAX_BYTES}（50KB）上限保护；超出时附加截断提示。</li>
 * </ul>
 */
@Component
public class FindTool implements ToolContributor {

    /** 默认最多返回的路径条数上限。 */
    private static final int DEFAULT_LIMIT = 1000;

    @Override
    public String displayLabel() {
        return "Find";
    }

    /**
     * 使用 Glob 模式递归搜索匹配的文件或目录路径。
     *
     * @param pattern Glob 匹配模式（如 {@code *.java}、{@code **&#47;*.json}）
     * @param path    搜索起始目录（可选，默认为当前工作目录）
     * @param limit   最大返回条数（可选，默认 1000）
     * @return 按字母不区分大小写排序的匹配相对路径列表，或未找到/截断提示
     */
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
                    // 跳过符号链接子目录，防止循环引用
                    if (Files.isSymbolicLink(dir) && !dir.equals(search)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    // 跳过被 .gitignore 忽略的整个子树
                    if (!dir.equals(search) && ignore.ignored(dir, true)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!dir.equals(search)) {
                        add(search.relativize(dir).toString().replace('\\', '/') + "/");
                    }
                    // 多收集 1 条（size > effectiveLimit）即提前终止遍历，用于标记结果数已达上限
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

                /** 校验相对路径是否匹配 Glob 模式，匹配则加入结果集。 */
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
        // 对输出文本按 50KB 字节上限做头部截断保护
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
