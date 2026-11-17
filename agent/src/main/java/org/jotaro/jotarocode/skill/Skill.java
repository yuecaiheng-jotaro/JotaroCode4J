package org.jotaro.jotarocode.skill;

import java.nio.file.Path;

/**
 * 一条已通过 frontmatter 与命名校验的 Agent 技能元数据。
 *
 * @param name                   技能唯一标识名称（仅允许小写字母、数字与连字符，如 {@code code-review}）
 * @param description            技能用途简述，用于在系统提示词中帮助模型判断何时加载该技能
 * @param file                   技能定义文件的规范化绝对路径（{@code SKILL.md} 或技能根目录下的 {@code .md} 文件）
 * @param directory              技能所在父目录的规范化绝对路径，用于解析技能内部引用的相对路径资源
 * @param disableModelInvocation 为 {@code true} 时，不在模型的系统提示词 {@code <available_skills>} 中暴露该技能，仅允许用户通过 {@code /skill:name} 显式触发
 */
public record Skill(
        String name,
        String description,
        Path file,
        Path directory,
        boolean disableModelInvocation
) {
}
