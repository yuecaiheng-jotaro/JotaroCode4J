package org.jotaro.jotarocode.agent.tools.coding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分层 {@code .gitignore} 规则解析与路径匹配器。
 *
 * <p>匹配规则说明：
 * <ul>
 *   <li>路径中任何一级名称为 {@code .git} 时直接忽略。</li>
 *   <li>从搜索根目录（{@code root}）逐层向下遍历到目标路径所在的父目录，按需懒加载并缓存每一层的 {@code .gitignore} 文件。</li>
 *   <li>支持注释（{@code #}）、取反（{@code !}）、仅目录匹配（末尾 {@code /}）、锚定当前目录（前导 {@code /}）以及多级路径/通配符匹配。</li>
 *   <li>遵循 Git 优先级：同一文件或子目录中后出现的规则（包括 {@code !} 取反规则）覆盖先出现的规则；但一旦父目录被忽略且未在父级取反，文件树遍历器会直接跳过整个子树。</li>
 * </ul>
 */
final class GitIgnore {

    /** 搜索起始根目录（规范化绝对路径）。 */
    private final Path root;
    /** 按目录缓存已解析的 {@code .gitignore} 规则列表，避免遍历文件树时重复读取磁盘。 */
    private final Map<Path, List<Rule>> rulesByDirectory = new HashMap<>();

    GitIgnore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /**
     * 判断指定路径是否被搜索根到该路径沿途的 {@code .gitignore} 规则忽略。
     *
     * @param path      待检查的路径
     * @param directory 该路径是否为目录
     * @return {@code true} 表示应忽略该路径
     */
    boolean ignored(Path path, boolean directory) {
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(root)) {
            return false;
        }
        Path relative = root.relativize(absolute);
        // 任何层级的 .git 目录及其内部文件一律忽略
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
        // 从 root 逐层向下计算相对子路径，依次应用每一层目录的 .gitignore 规则，后命中的规则覆盖先前的状态
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

    /**
     * 获取指定目录下的 {@code .gitignore} 规则列表（带缓存）。
     */
    private List<Rule> rules(Path directory) {
        return rulesByDirectory.computeIfAbsent(directory, this::load);
    }

    /**
     * 读取并解析指定目录下的 {@code .gitignore} 文件。
     */
    private List<Rule> load(Path directory) {
        Path file = directory.resolve(".gitignore");
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<Rule> rules = new ArrayList<>();
        try {
            for (String raw : Files.readAllLines(file)) {
                String line = raw.trim();
                // 跳过空行与注释行
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                // 解析前缀 '!'（取消忽略）
                boolean negated = line.startsWith("!");
                if (negated) {
                    line = line.substring(1).trim();
                }
                if (line.isEmpty()) {
                    continue;
                }
                // 解析后缀 '/'（仅匹配目录）
                boolean directoryOnly = line.endsWith("/");
                if (directoryOnly) {
                    line = line.substring(0, line.length() - 1);
                }
                // 解析前导 '/'（锚定在当前 .gitignore 所在目录）
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

    /** 将路径分隔符统一转换为 POSIX 正斜杠 {@code /}。 */
    private static String slash(Path path) {
        return path.toString().replace('\\', '/');
    }

    /**
     * 单条 {@code .gitignore} 规则定义。
     *
     * @param pattern       去除前导 {@code !}、{@code /} 和末尾 {@code /} 后的核心匹配模式
     * @param negated       是否为 {@code !} 取反规则
     * @param directoryOnly 是否仅匹配目录（原规则以 {@code /} 结尾）
     * @param anchored      是否锚定于当前目录（原规则以 {@code /} 开头）
     */
    private record Rule(String pattern, boolean negated, boolean directoryOnly, boolean anchored) {

        /**
         * 判断相对于当前 {@code .gitignore} 所在目录的路径是否命中本规则。
         * <ul>
         *   <li>若模式中包含 {@code /}：按相对路径匹配；若未显式锚定（{@code !anchored}），同时允许匹配任意子目录下的 {@code **&#47;pattern}。</li>
         *   <li>若模式中不含 {@code /}：仅取路径最后一级文件名进行匹配。</li>
         * </ul>
         */
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
