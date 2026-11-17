package org.jotaro.jotarocode.model.request;

import lombok.Data;

/**
 * {@code POST /api/sessions/{sessionId}/messages} 发送用户消息的请求体。
 * <p>
 * 空字符串或纯空白内容会在服务层校验时被拒绝并抛出 {@link org.jotaro.jotarocode.exception.BizException}。
 */
@Data
public class PromptRequest {
    /** 用户输入的提示词或指令文本（支持 {@code /skill:名称} 技能展开）。 */
    private String content;
}
