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
 * 发现 Agent Skills：只把名称、描述和路径放进系统提示，正文在任务匹配或 {@code /skill:name} 时再读取。
 *
 * <p>扫描顺序：先用户目录的 {@code ~/.pi/agent/skills}、{@code ~/.agents/skills}，
 * 再从工作目录向上直到含 {@code .git} 的仓库根，每一层看 {@code .pi/skills} 和 {@code .agents/skills}。
 * 同名技能保留先扫到的那个。
 */
@Component
@Slf4j
public class SkillCatalog {

    public List<Skill> skills() {
        return load(locations(Path.of(System.getProperty("user.dir"))));
    }

    public String promptSection() {
        return format(skills());
    }

    public String expand(String text) {
        return expand(text, skills());
    }

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

    static List<Path> locations(Path cwd) {
        Path home = Path.of(System.getProperty("user.home"));
        List<Path> roots = new ArrayList<>();
        roots.add(home.resolve(".pi/agent/skills"));
        roots.add(home.resolve(".agents/skills"));
        Path cursor = cwd.toAbsolutePath().normalize();
        Path stop = repositoryRoot(cursor);
        while (true) {
            roots.add(cursor.resolve(".pi/skills"));
            roots.add(cursor.resolve(".agents/skills"));
            if (cursor.equals(stop) || cursor.getParent() == null) {
                break;
            }
            cursor = cursor.getParent();
        }
        return roots;
    }

    List<Skill> load(List<Path> roots) {
        // 按 locations 的顺序扫描，同名技能由第一个有效定义占用。
        Map<String, Skill> skills = new LinkedHashMap<>();
        for (Path root : roots) {
            scan(root, true, skills);
        }
        return List.copyOf(skills.values());
    }

    /**
     * 目录里有 SKILL.md 就把它当作技能根，不再往下走。
     * 否则只在配置的根目录接受散落的 .md；子目录继续递归，但子目录里的散落 md 不算技能。
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

    private static String unquote(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
