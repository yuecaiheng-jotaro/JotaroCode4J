package org.jotaro.jotarocode.agent.tools.coding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从搜索根向下读取 .gitignore。后出现的规则覆盖先出现的规则，目录规则命中后不再进入该目录。
 */
final class GitIgnore {

    private final Path root;
    private final Map<Path, List<Rule>> rulesByDirectory = new HashMap<>();

    GitIgnore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    boolean ignored(Path path, boolean directory) {
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(root)) {
            return false;
        }
        Path relative = root.relativize(absolute);
        for (Path part : relative) {
            if (".git".equals(part.toString())) {
                return true;
            }
        }
        if (relative.getNameCount() == 0) {
            return false;
        }
        boolean ignored = false;
        Path cursor = root;
        for (int index = 0; index < relative.getNameCount(); index++) {
            String fromHere = slash(relative.subpath(index, relative.getNameCount()));
            boolean entryIsDirectory = directory || index < relative.getNameCount() - 1;
            for (Rule rule : rules(cursor)) {
                if (rule.matches(fromHere, entryIsDirectory)) {
                    ignored = !rule.negated;
                }
            }
            cursor = cursor.resolve(relative.getName(index));
        }
        return ignored;
    }

    private List<Rule> rules(Path directory) {
        return rulesByDirectory.computeIfAbsent(directory, this::load);
    }

    private List<Rule> load(Path directory) {
        Path file = directory.resolve(".gitignore");
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<Rule> rules = new ArrayList<>();
        try {
            for (String raw : Files.readAllLines(file)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                boolean negated = line.startsWith("!");
                if (negated) {
                    line = line.substring(1).trim();
                }
                if (line.isEmpty()) {
                    continue;
                }
                boolean directoryOnly = line.endsWith("/");
                if (directoryOnly) {
                    line = line.substring(0, line.length() - 1);
                }
                boolean anchored = line.startsWith("/");
                if (anchored) {
                    line = line.substring(1);
                }
                if (!line.isEmpty()) {
                    rules.add(new Rule(line, negated, directoryOnly, anchored));
                }
            }
        } catch (IOException exception) {
            return List.of();
        }
        return rules;
    }

    private static String slash(Path path) {
        return path.toString().replace('\\', '/');
    }

    private record Rule(String pattern, boolean negated, boolean directoryOnly, boolean anchored) {

        boolean matches(String relativePosix, boolean directory) {
            if (directoryOnly && !directory) {
                return false;
            }
            if (pattern.contains("/")) {
                if (GlobPatterns.matchesExact(pattern, relativePosix)) {
                    return true;
                }
                return !anchored && GlobPatterns.matchesExact("**/" + pattern, relativePosix);
            }
            String name = relativePosix.contains("/")
                    ? relativePosix.substring(relativePosix.lastIndexOf('/') + 1)
                    : relativePosix;
            return GlobPatterns.matchesExact(pattern, name);
        }
    }
}
