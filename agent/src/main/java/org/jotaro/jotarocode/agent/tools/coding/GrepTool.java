package org.jotaro.jotarocode.agent.tools.coding;

import org.jotaro.jotarocode.agent.tools.ToolContributor;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 文件内容搜索工具（{@code grep}）。
 *
 * <p>核心功能与边界处理：
 * <ul>
 *   <li>支持正则表达式或纯文本字面量（{@code literal = true}）搜索，支持忽略大小写（{@code ignoreCase = true}）和 Glob 文件名过滤。</li>
 *   <li>输出格式：命中行使用 {@code file:line: text}，前后上下文行使用 {@code file-line- text}，并确保相邻命中区间的上下文行不重复输出。</li>
 *   <li>二进制跳过：读取文件前 512 字节探测是否含 {@code \0} 字节，自动跳过二进制文件、符号链接及被 {@link GitIgnore} 忽略的路径。</li>
 *   <li>三重截断保护：单行超过 {@link ToolOutput#GREP_MAX_LINE_LENGTH}（500 字符）截断、命中数默认最多 100 条、总输出最多 50KB。</li>
 * </ul>
 */
@Component
public class GrepTool implements ToolContributor {

    /** 默认最大返回命中条数。 */
    private static final int DEFAULT_LIMIT = 100;

    @Override
    public String displayLabel() {
        return "Grep";
    }

    /**
     * 在指定目录或单个文件中搜索匹配模式的内容行。
     *
     * @param pattern    正则表达式或字面量搜索串
     * @param path       要搜索的目录或文件路径（默认当前工作目录）
     * @param glob       可选的文件路径 Glob 过滤模式
     * @param ignoreCase 是否忽略大小写（默认 {@code false}）
     * @param literal    是否将 {@code pattern} 当作普通字面量而非正则处理（默认 {@code false}）
     * @param context    每处命中前后展示的上下文行数（默认 0，上限 100）
     * @param limit      最大返回命中次数（默认 100）
     * @return 格式化后的匹配行与上下文行，附加截断说明
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "grep",
            description = "Search file contents for a pattern. Returns matching lines with file paths and line numbers. "
                    + "Respects .gitignore. Output is truncated to 100 matches or 50KB (whichever is hit first). "
                    + "Long lines are truncated to 500 chars."
    )
    public String grep(
            @ToolParam(description = "Search pattern (regex or literal string)") String pattern,
            @ToolParam(description = "Directory or file to search (default: current directory)", required = false) String path,
            @ToolParam(description = "Filter files by glob pattern, relative to path. A pattern without '/' matches file names at any depth", required = false) String glob,
            @ToolParam(description = "Case-insensitive search (default: false)", required = false) Boolean ignoreCase,
            @ToolParam(description = "Treat pattern as a literal string instead of a regex (default: false)", required = false) Boolean literal,
            @ToolParam(description = "Number of lines to show before and after each match (default: 0)", required = false) Integer context,
            @ToolParam(description = "Maximum number of matches to return (default: 100)", required = false) Integer limit
    ) {
        if (pattern == null || pattern.isEmpty()) {
            throw new IllegalArgumentException("pattern must not be empty");
        }
        Path search = ToolPaths.resolve(path == null || path.isBlank() ? "." : path);
        if (!Files.exists(search)) {
            throw new IllegalArgumentException("Path not found: " + search);
        }
        int flags = ignoreCase != null && ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
        Pattern compiled;
        try {
            compiled = Pattern.compile(literal != null && literal ? Pattern.quote(pattern) : pattern, flags);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid regex: " + exception.getMessage());
        }
        int effectiveLimit = Math.max(1, limit == null ? DEFAULT_LIMIT : limit);
        int contextLines = context == null || context < 0 ? 0 : Math.min(context, 100);
        Search state = new Search(search, compiled, glob, effectiveLimit, contextLines);
        try {
            if (Files.isDirectory(search)) {
                GitIgnore ignore = new GitIgnore(search);
                Files.walkFileTree(search, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (state.done) {
                            return FileVisitResult.TERMINATE;
                        }
                        if (Files.isSymbolicLink(dir) && !dir.equals(search)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        if (!dir.equals(search) && ignore.ignored(dir, true)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (state.done || Files.isSymbolicLink(file) || ignore.ignored(file, false)) {
                            return state.done ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                        }
                        state.scan(file, true);
                        return state.done ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                    }
                });
            } else {
                state.scan(search, false);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Search failed: " + exception.getMessage(), exception);
        }
        if (state.matchCount == 0) {
            return "No matches found";
        }
        ToolOutput.Truncation truncation = ToolOutput.head(String.join("\n", state.lines), Integer.MAX_VALUE, ToolOutput.MAX_BYTES);
        String output = truncation.content();
        List<String> notices = new ArrayList<>();
        if (state.provedMore) {
            notices.add(effectiveLimit + " matches limit reached. Use limit=" + (effectiveLimit * 2)
                    + " for more, or refine pattern");
        }
        if (truncation.truncated()) {
            notices.add(ToolOutput.formatSize(ToolOutput.MAX_BYTES) + " limit reached");
        }
        if (state.linesTruncated) {
            notices.add("Some lines truncated to " + ToolOutput.GREP_MAX_LINE_LENGTH + " chars. Use read tool to see full lines");
        }
        if (!notices.isEmpty()) {
            output += "\n\n[" + String.join(". ", notices) + "]";
        }
        return output;
    }

    /**
     * 单次 Grep 搜索过程的有状态扫描器。
     */
    private static final class Search {
        private final Path root;
        private final Pattern pattern;
        private final String glob;
        private final int limit;
        private final int context;
        /** 已收集的格式化输出行列表。 */
        private final List<String> lines = new ArrayList<>();
        /** 已命中的核心匹配行数（不含上下文行）。 */
        private int matchCount;
        /** 是否已证实除已返回的 {@code limit} 条外仍存在更多匹配项。 */
        private boolean provedMore;
        /** 是否存在因超过 500 字符而被截断的单行。 */
        private boolean linesTruncated;
        /** 是否已完成全部所需扫描，可提前终止文件树遍历。 */
        private boolean done;

        private Search(Path root, Pattern pattern, String glob, int limit, int context) {
            this.root = root;
            this.pattern = pattern;
            this.glob = glob;
            this.limit = limit;
            this.context = context;
        }

        /**
         * 扫描单个文本文件，维护前文环形缓冲（{@code before}）与后文剩余计数（{@code trailing}）以输出上下文。
         */
        private void scan(Path file, boolean relativeToRoot) {
            if (done || !Files.isRegularFile(file) || isBinary(file) || !globMatches(file, relativeToRoot)) {
                return;
            }
            // 若前序文件已凑够 limit 条命中，只需探测当前文件是否还有第 limit+1 条命中以设置 provedMore
            if (matchCount >= limit) {
                if (containsMatch(file)) {
                    provedMore = true;
                    done = true;
                }
                return;
            }
            List<Pending> before = new ArrayList<>();
            int trailing = 0;
            boolean limitReached = false;
            int lastEmitted = 0;
            int lineNumber = 0;
            try (BufferedReader reader = Files.newBufferedReader(file)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    boolean match = pattern.matcher(line).find();
                    if (!limitReached) {
                        if (match) {
                            // 先补齐该命中行之前尚未输出过的上文行
                            int contextStart = Math.max(1, lineNumber - context);
                            for (Pending pending : before) {
                                if (pending.number >= contextStart && pending.number > lastEmitted) {
                                    emit(file, relativeToRoot, pending.number, pending.text, false);
                                    lastEmitted = pending.number;
                                }
                            }
                            emit(file, relativeToRoot, lineNumber, line, true);
                            lastEmitted = lineNumber;
                            matchCount++;
                            trailing = context;
                            if (matchCount >= limit) {
                                limitReached = true;
                            }
                        } else if (trailing > 0 && lineNumber > lastEmitted) {
                            // 输出上一处命中行之后的下文行
                            emit(file, relativeToRoot, lineNumber, line, false);
                            lastEmitted = lineNumber;
                            trailing--;
                        }
                    } else if (trailing > 0 && lineNumber > lastEmitted) {
                        // 已达命中上限，但最后一条命中的下文还没输出完，继续补齐下文
                        emit(file, relativeToRoot, lineNumber, line, false);
                        lastEmitted = lineNumber;
                        trailing--;
                        if (match) {
                            provedMore = true;
                        }
                    } else if (match) {
                        provedMore = true;
                        done = true;
                        return;
                    }
                    // 维护固定长度为 context 的滑动窗口以备后续命中输出上文
                    if (context > 0) {
                        before.add(new Pending(lineNumber, line));
                        if (before.size() > context) {
                            before.remove(0);
                        }
                    }
                    if (limitReached && trailing == 0 && provedMore) {
                        done = true;
                        return;
                    }
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot read " + file + ": " + exception.getMessage(), exception);
            }
            if (limitReached && provedMore) {
                done = true;
            }
        }

        /**
         * 将单行格式化后加入输出列表，超过 500 字符时截断。
         */
        private void emit(Path file, boolean relativeToRoot, int lineNumber, String text, boolean match) {
            String shown = text;
            if (shown.length() > ToolOutput.GREP_MAX_LINE_LENGTH) {
                shown = shown.substring(0, ToolOutput.GREP_MAX_LINE_LENGTH);
                linesTruncated = true;
            }
            String display = relativeToRoot ? root.relativize(file).toString().replace('\\', '/') : file.getFileName().toString();
            lines.add(match
                    ? display + ":" + lineNumber + ": " + shown
                    : display + "-" + lineNumber + "- " + shown);
        }

        /** 检查文件路径是否符合 {@code glob} 过滤条件。 */
        private boolean globMatches(Path file, boolean relativeToRoot) {
            if (glob == null || glob.isBlank()) {
                return true;
            }
            String relative = relativeToRoot
                    ? root.relativize(file).toString().replace('\\', '/')
                    : file.getFileName().toString();
            return GlobPatterns.matchesFilter(glob, relative);
        }

        /** 快速判断文件中是否包含至少一处正则匹配。 */
        private boolean containsMatch(Path file) {
            try (BufferedReader reader = Files.newBufferedReader(file)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (pattern.matcher(line).find()) {
                        return true;
                    }
                }
                return false;
            } catch (IOException exception) {
                return false;
            }
        }

        /**
         * 读取文件开头最多 512 字节，若存在 NUL（{@code 0x00}）字节则判定为二进制文件。
         */
        private static boolean isBinary(Path file) {
            try (InputStream input = Files.newInputStream(file)) {
                byte[] probe = input.readNBytes(512);
                for (byte value : probe) {
                    if (value == 0) {
                        return true;
                    }
                }
                return false;
            } catch (IOException exception) {
                return true;
            }
        }
    }

    /** 暂存的待输出上文行。 */
    private record Pending(int number, String text) {
    }
}
