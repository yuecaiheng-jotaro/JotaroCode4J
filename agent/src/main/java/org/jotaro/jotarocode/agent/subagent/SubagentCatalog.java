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
 * 子代理（Subagent）角色定义目录与加载器。
 *
 * <p>设计机制与覆盖优先级（由低到高）：
 * <ol>
 *   <li><b>内置定义（Built-ins）</b>：默认提供只读探索型 {@code explore}（仅允许 {@code read/grep/find/ls}）
 *       和通用执行型 {@code general}（允许除 {@code subagent} 外的全部工具）。</li>
 *   <li><b>用户全局定义</b>：扫描用户主目录下的 {@code ~/.jotaro/agents/*.md}。</li>
 *   <li><b>项目级就近定义</b>：从当前工作目录向上回溯（不超过 Git 仓库根目录 {@code .git}），找到最近一层存在的 {@code .jotaro/agents/*.md}。</li>
 * </ol>
 * 后加载的同名子代理定义会覆盖先加载的定义，使项目可以按需定制专属子代理或改写内置角色。
 */
@Slf4j
@Component
public class SubagentCatalog {

    /**
     * 获取当前工作目录下生效的全部子代理定义列表。
     *
     * @return 按加载顺序合并后的不可变子代理定义列表
     */
    public List<SubagentDefinition> agents() {
        return assemble(locations(Path.of("").toAbsolutePath()));
    }

    /**
     * 返回系统内置的两个基础子代理定义：{@code explore} 与 {@code general}。
     *
     * @return 内置子代理定义列表
     */
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
     * 将内置定义与指定目录列表中的外部定义按顺序合并，后面的同名定义覆盖前面的，内置定义垫在最底层。
     *
     * @param directories 待扫描的目录列表（按优先级从低到高排列）
     * @return 合并后的不可变子代理定义列表
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

    /**
     * 依次扫描给定目录下的 {@code .md} 文件并解析为 {@link SubagentDefinition}。
     * 单个文件读取或格式不合法时仅记录警告并跳过，不会中断其他子代理的加载。
     *
     * @param directories 待扫描的目录列表
     * @return 从文件成功解析出的子代理定义列表
     */
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

    /**
     * 计算需要扫描的子代理定义目录列表：先放入 {@code ~/.jotaro/agents}，再放入距离 {@code cwd} 最近的 {@code .jotaro/agents}。
     *
     * @param cwd 当前工作目录路径
     * @return 候选目录列表
     */
    static List<Path> locations(Path cwd) {
        List<Path> roots = new ArrayList<>();
        roots.add(Path.of(System.getProperty("user.home")).resolve(".jotaro/agents"));
        Path project = nearest(cwd.toAbsolutePath().normalize(), ".jotaro/agents");
        if (project != null) {
            roots.add(project);
        }
        return roots;
    }

    /**
     * 解析 Markdown 格式的子代理定义文本（包含头部 YAML Frontmatter 与正文系统提示词）。
     *
     * <p>格式与边界约束：
     * <ul>
     *   <li>必须以 {@code ---\n} 开头并以 {@code \n---} 闭合 Frontmatter，否则返回 {@link Optional#empty()} 或抛出异常。</li>
     *   <li>必须包含非空的 {@code name} 和 {@code description}，缺少时返回 {@link Optional#empty()}。</li>
     *   <li>{@code name} 仅允许小写字母、数字和连字符（kebab-case），且长度不超过 64。</li>
     *   <li>{@code description} 超过 1024 字符时会被截断，避免撑爆父代理工具说明。</li>
     *   <li>{@code tools}（可选）支持逗号分隔或方括号包裹的列表；{@code model}（可选）为空白时视为 {@code null}（跟随父模型）。</li>
     * </ul>
     *
     * @param raw Markdown 原始文本
     * @return 解析出的 {@link SubagentDefinition}，不满足前置条件时返回 {@link Optional#empty()}
     */
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
            // 跳过空行、注释行以及缩进的多行子项
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

    /**
     * 解析 Frontmatter 中的 {@code tools} 字段（如 {@code [read, grep]} 或 {@code read, grep}）。
     *
     * @param raw 原始工具配置字符串
     * @return 工具名列表；若未配置或解析后为空则返回 {@code null}（代表不限制工具白名单）
     */
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

    /**
     * 从 {@code cwd} 开始逐级向上查找包含指定相对子目录（如 {@code .jotaro/agents}）的目录，遇到 Git 仓库根目录即停止向上越界。
     *
     * @param cwd      起始工作目录
     * @param relative 目标相对路径
     * @return 匹配到的第一个目录路径，未找到则返回 {@code null}
     */
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

    /**
     * 向上查找包含 {@code .git} 目录的仓库根目录；若不在 Git 仓库内则返回 {@code cwd} 本身。
     *
     * @param cwd 起始目录
     * @return Git 仓库根目录或 {@code cwd}
     */
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

    /**
     * 列出指定目录下的直接子项，并按文件名升序排列，保证加载顺序确定可控。
     *
     * @param directory 目标目录
     * @return 排序后的文件路径列表（目录不存在或读取失败时返回空列表）
     */
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

    /**
     * 去除字符串首尾成对包裹的单引号或双引号。
     *
     * @param value 原始字段值
     * @return 去引号后的字符串
     */
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
