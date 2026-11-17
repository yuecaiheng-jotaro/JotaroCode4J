package org.jotaro.jotarocode.model.response;

import lombok.Builder;
import lombok.Data;

/**
 * 创建会话接口（{@code POST /api/sessions}）的返回数据。
 * <p>
 * 包含新分配的会话 ID 及实际选中的模型名称，前端拿到 {@code sessionId} 后即可建立 SSE 连接。
 */
@Data
@Builder
public class CreateSessionResponse {
    /** 新建会话的唯一标识（UUID）。 */
    private String sessionId;
    /** 该会话当前绑定的模型名称。 */
    private String model;
}
