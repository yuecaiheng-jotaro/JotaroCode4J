package org.jotaro.jotarocode.model.response;

import lombok.Builder;
import lombok.Data;

/** 新会话的 id，以及实际选中的模型名。页面接着用这个 id 去连 SSE。 */
@Data
@Builder
public class CreateSessionResponse {
    private String sessionId;
    private String model;
}
