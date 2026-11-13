package org.jotaro.jotarocode.model.request;

import lombok.Data;

/** {@code POST /sessions/{id}/messages} 的正文。空内容会在服务层被拒绝。 */
@Data
public class PromptRequest {
    private String content;
}
