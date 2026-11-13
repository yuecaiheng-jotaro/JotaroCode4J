package org.jotaro.jotarocode.agent.subagent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 子代理名单。内置 {@code explore}（只读）和 {@code general}（可改文件、可执行命令）。
 * 同名文件会覆盖内置定义：先读 {@code ~/.pi/agent/agents}，再读从工作目录向上找到的最近 {@code .pi/agents}。
 */
@Slf4j
@Component
public class SubagentCatalog {

    public List<SubagentDefinition> agents() {
        return assemble(locations(Path.of("").toAbsolutePath()));
    }

    static List<SubagentDefinition> builtins() {
        return List.of(
                new SubagentDefinition(
                        "explore",
                        "只读搜索代码。可以读文件、列目录、搜内容和按文件名查找，不能修改文件或执行命令。",
                        List.of("read", "grep", "find", "ls"),
                        null,
                        """
                                你负责只读地摸清代码。用 read、grep、find、ls 找答案。
                                不要修改文件，不要执行命令。回答时给出路径和关键内容。"""
                ),
                new SubagentDefinition(
                        "general",
                        "通用执行。可以读改文件并执行命令，用来完成一件相对独立的实现或调查。",
                        null,
                        null,
                        """
                                你负责把交给你的任务做完。可以读改文件，也可以执行命令。
                                做完后用结果回复，不要复述整段过程。"""
                )
        );
    }

    /**
     * 目录按顺序合并，后面的同名定义覆盖前面的。内置定义垫在最底下。
     */
    List<SubagentDefinition> assemble(List<Path> directories) {
        Map<String, SubagentDefinition> agents = new LinkedHashMap<>();
        for (SubagentDefinition builtin : builtins()) {
            agents.put(builtin.name(), builtin);
        }
        for (SubagentDefinition loaded : load(directories)) {
            SubagentDefinition previous = agents.put(loaded.name(), loaded);
            if (previous != null) {
                log.info("子代理 {} 被文件定义覆盖", loaded.name());
            }
        }
        return List.copyOf(agents.values());
    }

    List<SubagentDefinition> load(List<Path> directories) {
        Map<String, SubagentDefinition> agents = new LinkedHashMap<>();
        if (directories == null) {
            return List.of();
        }
        for (Path directory : directories) {
            for (Path file : list(directory)) {
                if (!file.getFileName().toString().endsWith(".md") || !Files.isRegularFile(file)) {
                    continue;
                }
                try {
                    Optional<SubagentDefinition> parsed = parse(Files.readString(file, StandardCharsets.UTF_8));
                    parsed.ifPresent(agent -> agents.put(agent.name(), agent));
                } catch (IOException | IllegalArgumentException exception) {
                    log.warn("跳过子代理 {}: {}", file, exception.getMessage());
                }
            }
        }
        return List.copyOf(agents.values());
    }

    static List<Path> locations(Path cwd) {
        List<Path> roots = new ArrayList<>();
        roots.add(Path.of(System.getProperty("user.home")).resolve(".pi/agent/agents"));
        Path project = nearest(cwd.toAbsolutePath().normalize(), ".pi/agents");
        if (project != null) {
            roots.add(project);
        }
        return roots;
    }

    static Optional<SubagentDefinition> parse(String raw) {
        String text = raw.replace("\r\n", "\n");
        if (!text.startsWith("---\n")) {
            return Optional.empty();
        }
        int close = text.indexOf("\n---", 3);
        if (close < 0) {
            throw new IllegalArgumentException("frontmatter 没有结束");
        }
        Map<String, String> frontmatter = new LinkedHashMap<>();
        for (String line : text.substring(4, close).lines().toList()) {
            if (line.isBlank() || line.charAt(0) == '#' || line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            frontmatter.put(line.substring(0, colon).trim(), unquote(line.substring(colon + 1).trim()));
        }
        String name = frontmatter.get("name");
        String description = frontmatter.get("description");
        if (name == null || name.isBlank() || description == null || description.isBlank()) {
            return Optional.empty();
        }
        name = name.trim();
        if (!name.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || name.length() > 64) {
            throw new IllegalArgumentException("非法子代理名称: " + name);
        }
        if (description.length() > 1024) {
            description = description.substring(0, 1024);
        }
        String body = text.substring(close + 4);
        if (body.startsWith("\n")) {
            body = body.substring(1);
        }
        String model = frontmatter.get("model");
        if (model != null && model.isBlank()) {
            model = null;
        }
        return Optional.of(new SubagentDefinition(
                name,
                description.trim(),
                tools(frontmatter.get("tools")),
                model,
                body.strip()
        ));
    }

    private static List<String> tools(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        if (text.startsWith("[") && text.endsWith("]")) {
            text = text.substring(1, text.length() - 1);
        }
        List<String> names = new ArrayList<>();
        for (String part : text.split(",")) {
            String name = part.trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names.isEmpty() ? null : List.copyOf(names);
    }

    private static Path nearest(Path cwd, String relative) {
        Path stop = repositoryRoot(cwd);
        Path cursor = cwd;
        while (true) {
            Path candidate = cursor.resolve(relative);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            if (cursor.equals(stop) || cursor.getParent() == null) {
                return null;
            }
            cursor = cursor.getParent();
        }
    }

    private static Path repositoryRoot(Path cwd) {
        Path cursor = cwd;
        while (cursor != null) {
            if (Files.isDirectory(cursor.resolve(".git"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        return cwd;
    }

    private static List<Path> list(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            stream.forEach(files::add);
        } catch (IOException exception) {
            return List.of();
        }
        files.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return files;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char quote = value.charAt(0);
            if ((quote == '"' || quote == '\'') && value.charAt(value.length() - 1) == quote) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
