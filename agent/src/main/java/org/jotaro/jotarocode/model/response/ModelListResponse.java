package org.jotaro.jotarocode.model.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 可用模型列表查询接口（{@code GET /api/models}）的返回数据。
 */
@Data
@Builder
public class ModelListResponse {
    /** 已配置有效 API Key 的可用模型名称列表（供前端下拉框渲染）。 */
    private List<String> models;
    /** 当前默认选中的模型名称。 */
    private String model;
}
