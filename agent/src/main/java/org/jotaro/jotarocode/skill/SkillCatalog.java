package org.jotaro.jotarocode.skill;

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
 * Agent Skills（技能目录）发现、解析与展开组件。
 *
 * <p>采用按需加载设计：系统提示词中仅注入技能的 {@code name}、{@code description} 和 {@code location}，
 * 完整的技能正文在模型根据任务描述调用 {@code read} 工具读取，或用户显式输入 {@code /skill:name} 指令时才载入上下文。
 *
 * <p>目录扫描优先级（同名技能先扫到的生效，后续同名项忽略并打印告警）：
 * <ol>
 *   <li>用户级目录：{@code ~/.jotaro/skills}、{@code ~/.agents/skills}</li>
 *   <li>工作区级目录：从当前工作目录（{@code user.dir}）逐层向上回溯直至包含 {@code .git} 的仓库根目录，
 *       依次扫描每一层的 {@code .jotaro/skills} 和 {@code .agents/skills}</li>
 * </ol>
 */
@Component
@Slf4j
public class SkillCatalog {

    /**
     * 扫描当前工作目录关联的所有技能路径，返回去重后的有效技能列表。
     */
    public List<Skill> skills() {
        return load(locations(Path.of(System.getProperty("user.dir"))));
    }

    /**
     * 生成拼入系统提示词的 {@code <available_skills>} XML 片段。
     */
    public String promptSection() {
        return format(skills());
    }

    /**
     * 若用户输入以 {@code /skill:name} 开头，则将其展开为包含技能全文的 {@code <skill>} 提示块。
     */
    public String expand(String text) {
        return expand(text, skills());
    }

    /**
     * 将允许模型自动调用的技能（{@code disableModelInvocation == false}）格式化为 XML 摘要块。
     */
    String format(List<Skill> loaded) {
        List<Skill> visible = loaded.stream().filter(skill -> !skill.disableModelInvocation()).toList();
        if (visible.isEmpty()) {
            return "";
        }
        StringBuilder section = new StringBuilder();
        section.append("The following skills provide specialized instructions for specific tasks.\n");
        section.append("Use the read tool to load a skill's file when the task matches its description.\n");
        section.append("Resolve relative paths against the skill's directory (parent of SKILL.md). Use absolute paths in tool calls.\n\n");
        section.append("<available_skills>\n");
        for (Skill skill : visible) {
            section.append("  <skill>\n");
            section.append("    <name>").append(escapeXml(skill.name())).append("</name>\n");
            section.append("    <description>").append(escapeXml(skill.description())).append("</description>\n");
            section.append("    <location>").append(escapeXml(skill.file().toString())).append("</location>\n");
            section.append("  </skill>\n");
        }
        section.append("</available_skills>");
        return section.toString();
    }

    /**
     * 解析 {@code /skill:name [args]} 命令，读取对应技能文件去掉 frontmatter 后的正文并包装为 {@code <skill>} 标签。
     * 若输入不是技能命令或未找到对应技能，则原样返回输入文本。
     */
    String expand(String text, List<Skill> loaded) {
        if (text == null || !text.startsWith("/skill:")) {
            return text;
        }
        int space = text.indexOf(' ');
        String name = space < 0 ? text.substring("/skill:".length()) : text.substring("/skill:".length(), space);
        String args = space < 0 ? "" : text.substring(space + 1).trim();
        Optional<Skill> match = loaded.stream().filter(skill -> skill.name().equals(name)).findFirst();
        if (match.isEmpty()) {
            return text;
        }
        Skill skill = match.get();
        try {
            String body = body(Files.readString(skill.file(), StandardCharsets.UTF_8)).trim();
            String block = "<skill name=\"" + escapeXml(skill.name()) + "\" location=\"" + escapeXml(skill.file().toString()) + "\">\n"
                    + "References are relative to " + skill.directory() + ".\n\n"
                    + body + "\n</skill>";
            return args.isEmpty() ? block : block + "\n\n" + args;
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取技能 " + name + ": " + exception.getMessage(), exception);
        }
    }

    /**
     * 计算所有待扫描的技能根目录列表：先用户主目录，再从 {@code cwd} 向上回溯至 Git 仓库根目录。
     */
    static List<Path> locations(Path cwd) {
        Path home = Path.of(System.getProperty("user.home"));
        List<Path> roots = new ArrayList<>();
        roots.add(home.resolve(".jotaro/skills"));
        roots.add(home.resolve(".agents/skills"));
        Path cursor = cwd.toAbsolutePath().normalize();
        Path stop = repositoryRoot(cursor);
        while (true) {
            roots.add(cursor.resolve(".jotaro/skills"));
            roots.add(cursor.resolve(".agents/skills"));
            if (cursor.equals(stop) || cursor.getParent() == null) {
                break;
            }
            cursor = cursor.getParent();
        }
        return roots;
    }

    /**
     * 按候选根目录顺序扫描并加载技能，同名技能保留最先扫描到的定义。
     */
    List<Skill> load(List<Path> roots) {
        // 按 locations 的顺序扫描，同名技能由第一个有效定义占用。
        Map<String, Skill> skills = new LinkedHashMap<>();
        for (Path root : roots) {
            scan(root, true, skills);
        }
        return List.copyOf(skills.values());
    }

    /**
     * 递归扫描技能目录：
     * <ul>
     *   <li>若当前目录下存在 {@code SKILL.md}，直接将其作为技能定义文件加载，不再递归其子目录（子目录可能是脚本或参考资源）。</li>
     *   <li>若无 {@code SKILL.md}，仅在顶层技能根目录（{@code includeRootFiles == true}）下接受散落的 {@code *.md} 文件；
     *       对非隐藏且非 {@code node_modules}/{@code target} 的子目录继续递归查找 {@code SKILL.md}。</li>
     * </ul>
     */
    private void scan(Path directory, boolean includeRootFiles, Map<String, Skill> skills) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        Path declared = directory.resolve("SKILL.md");
        if (Files.isRegularFile(declared)) {
            accept(declared, skills);
            return;
        }
        List<Path> children = list(directory);
        for (Path child : children) {
            String name = child.getFileName().toString();
            if (name.startsWith(".") || "node_modules".equals(name) || "target".equals(name)) {
                continue;
            }
            if (Files.isDirectory(child)) {
                scan(child, false, skills);
            } else if (includeRootFiles && name.endsWith(".md") && Files.isRegularFile(child)) {
                accept(child, skills);
            }
        }
    }

    /**
     * 读取并解析单个技能文件，校验通过后放入技能表；若发生重名则保留先加载的技能并记录告警。
     */
    private void accept(Path file, Map<String, Skill> skills) {
        try {
            Optional<Skill> skill = parse(file, Files.readString(file, StandardCharsets.UTF_8));
            if (skill.isEmpty()) {
                return;
            }
            Skill loaded = skill.get();
            Skill existing = skills.putIfAbsent(loaded.name(), loaded);
            if (existing != null) {
                log.warn("技能名称 {} 冲突，保留 {}，忽略 {}", loaded.name(), existing.file(), loaded.file());
            }
        } catch (IOException | IllegalArgumentException exception) {
            log.warn("跳过技能 {}: {}", file, exception.getMessage());
        }
    }

    /**
     * 解析技能 Markdown 内容中的 frontmatter 并构造 {@link Skill}：
     * <ul>
     *   <li>缺少 {@code description} 时视为普通文档，返回 {@link Optional#empty()}。</li>
     *   <li>未显式指定 {@code name} 时默认取父目录名。</li>
     *   <li>{@code name} 必须符合 {@code [a-z0-9]+(-[a-z0-9]+)*} 且长度不超过 64，否则抛出 {@link IllegalArgumentException}。</li>
     *   <li>{@code description} 超过 1024 字符时截断。</li>
     * </ul>
     */
    static Optional<Skill> parse(Path file, String raw) {
        Map<String, String> frontmatter = frontmatter(raw);
        String description = frontmatter.get("description");
        if (description == null || description.isBlank()) {
            return Optional.empty();
        }
        String name = frontmatter.get("name");
        if (name == null || name.isBlank()) {
            name = file.getParent() == null ? file.getFileName().toString() : file.getParent().getFileName().toString();
        }
        name = name.trim();
        if (!name.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || name.length() > 64) {
            throw new IllegalArgumentException("非法技能名称: " + name);
        }
        if (description.length() > 1024) {
            description = description.substring(0, 1024);
        }
        boolean disabled = "true".equalsIgnoreCase(frontmatter.get("disable-model-invocation"));
        return Optional.of(new Skill(name, description.trim(), file.toAbsolutePath().normalize(), file.getParent().toAbsolutePath().normalize(), disabled));
    }

    /**
     * 轻量解析 Markdown 顶部的 YAML frontmatter（位于首行 {@code ---} 与结束 {@code ---} 之间）。
     * 支持单行键值对、成对引号包裹字符串以及 {@code |} / {@code >} 引导的缩进多行文本块。
     */
    private static Map<String, String> frontmatter(String raw) {
        String text = raw.replace("\r\n", "\n");
        if (!text.startsWith("---\n")) {
            return Map.of();
        }
        int close = text.indexOf("\n---", 3);
        if (close < 0) {
            throw new IllegalArgumentException("frontmatter 没有结束");
        }
        Map<String, String> values = new LinkedHashMap<>();
        List<String> lines = text.substring(4, close).lines().toList();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isBlank() || line.trim().startsWith("#")) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0 || line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            // 处理 YAML 多行块标量（| 或 >），持续读取后续缩进行
            if ("|".equals(value) || ">".equals(value)) {
                StringBuilder block = new StringBuilder();
                while (index + 1 < lines.size()) {
                    String next = lines.get(index + 1);
                    if (next.isBlank()) {
                        block.append('\n');
                        index++;
                        continue;
                    }
                    if (!next.startsWith(" ") && !next.startsWith("\t")) {
                        break;
                    }
                    if (!block.isEmpty()) {
                        block.append('\n');
                    }
                    block.append(next.stripLeading());
                    index++;
                }
                value = block.toString().trim();
            } else {
                value = unquote(value);
            }
            values.put(key, value);
        }
        return values;
    }

    /**
     * 剥离 Markdown 文件头部的 YAML frontmatter，返回纯正文内容。
     */
    static String body(String raw) {
        String text = raw.replace("\r\n", "\n");
        if (!text.startsWith("---\n")) {
            return text;
        }
        int close = text.indexOf("\n---", 3);
        if (close < 0) {
            return text;
        }
        String body = text.substring(close + 4);
        return body.startsWith("\n") ? body.substring(1) : body;
    }

    /**
     * 列出指定目录下的直接子项，并按文件名升序排序以确保扫描结果稳定。
     */
    private static List<Path> list(Path directory) {
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            stream.forEach(children::add);
        } catch (IOException exception) {
            return List.of();
        }
        children.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return children;
    }

    /**
     * 从当前工作目录向上查找包含 {@code .git} 目录的仓库根路径；若未找到则返回 {@code cwd} 本身。
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
     * 剥离字符串首尾成对的双引号或单引号。
     */
    private static String unquote(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * 对拼接到 XML 标签中的文本进行基础实体转义。
     */
    private static String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
