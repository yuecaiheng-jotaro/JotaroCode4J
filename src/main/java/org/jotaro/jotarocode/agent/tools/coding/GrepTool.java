package org.jotaro.jotarocode.agent.tools.coding;

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
 * 在文件内容里搜索。命中行格式是 {@code file:line: text}，上下文行用 {@code file-line- text}。
 * 单行超过 500 字符会截断。默认最多 100 条。
 */
@Component
public class GrepTool {

    private static final int DEFAULT_LIMIT = 100;

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

    private static final class Search {
        private final Path root;
        private final Pattern pattern;
        private final String glob;
        private final int limit;
        private final int context;
        private final List<String> lines = new ArrayList<>();
        private int matchCount;
        private boolean provedMore;
        private boolean linesTruncated;
        private boolean done;

        private Search(Path root, Pattern pattern, String glob, int limit, int context) {
            this.root = root;
            this.pattern = pattern;
            this.glob = glob;
            this.limit = limit;
            this.context = context;
        }

        private void scan(Path file, boolean relativeToRoot) {
            if (done || !Files.isRegularFile(file) || isBinary(file) || !globMatches(file, relativeToRoot)) {
                return;
            }
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
                            emit(file, relativeToRoot, lineNumber, line, false);
                            lastEmitted = lineNumber;
                            trailing--;
                        }
                    } else if (trailing > 0 && lineNumber > lastEmitted) {
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

        private boolean globMatches(Path file, boolean relativeToRoot) {
            if (glob == null || glob.isBlank()) {
                return true;
            }
            String relative = relativeToRoot
                    ? root.relativize(file).toString().replace('\\', '/')
                    : file.getFileName().toString();
            return GlobPatterns.matchesFilter(glob, relative);
        }

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

    private record Pending(int number, String text) {
    }
}
