package org.jotaro.jotarocode.model.request;

import lombok.Data;

/**
 * 创建会话及切换模型时复用的请求体。
 * <p>
 * 两个字段均支持缺省：创建会话时若为空，则自动采用配置文件中的默认模型与默认系统提示词。
 */
@Data
public class CreateSessionRequest {
    /** 期望使用的模型名称（如 {@code deepseek-chat}、{@code glm-4.6}）。 */
    private String model;
    /** 自定义系统提示词（为空时使用全局默认系统提示词）。 */
    private String systemPrompt;
}
