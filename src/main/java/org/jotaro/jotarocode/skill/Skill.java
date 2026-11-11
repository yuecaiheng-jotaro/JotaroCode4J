package org.jotaro.jotarocode.skill;

import java.nio.file.Path;

/**
 * 一条已通过校验的技能。{@code file} 是 SKILL.md 或根目录下的 md，{@code directory} 是解析相对路径用的父目录。
 * {@code disableModelInvocation} 为真时，模型的系统提示里看不到它。
 */
public record Skill(
        String name,
        String description,
        Path file,
        Path directory,
        boolean disableModelInvocation
) {
}
