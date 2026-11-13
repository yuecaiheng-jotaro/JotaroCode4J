package org.jotaro.jotarocode.model.request;

import lombok.Data;

/** 创建会话，以及切换模型时复用。两个字段都可空，空则用配置里的默认模型和系统提示。 */
@Data
public class CreateSessionRequest {
    private String model;
    private String systemPrompt;
}
