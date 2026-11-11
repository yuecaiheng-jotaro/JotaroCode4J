package org.jotaro.jotarocode.model.response;

import lombok.Builder;
import lombok.Data;

/**
 * 给页面的技能摘要。{@code commandOnly} 对应 frontmatter 的 {@code disable-model-invocation}，
 * 这种技能不会出现在模型的系统提示里。
 */
@Data
@Builder
public class SkillView {
    private String name;
    private String description;
    private boolean commandOnly;
}
