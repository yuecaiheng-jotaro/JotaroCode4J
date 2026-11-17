package org.jotaro.jotarocode.model.response;

import lombok.Builder;
import lombok.Data;

/**
 * 面向前端展示的单个技能（Skill）摘要信息。
 * <p>
 * {@code commandOnly} 对应技能文件 frontmatter 中的 {@code disable-model-invocation}：
 * 当为 {@code true} 时，该技能不会拼入模型的系统提示词，仅允许用户通过 {@code /skill:名称} 显式触发。
 */
@Data
@Builder
public class SkillView {
    /** 技能名称（用于 {@code /skill:名称} 调用）。 */
    private String name;
    /** 技能用途简述。 */
    private String description;
    /** 是否仅限用户通过命令显式调用（禁止模型自主触发）。 */
    private boolean commandOnly;
}
