package org.jotaro.jotarocode.model.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** {@code models} 是下拉框选项，{@code model} 是配置里的默认项。 */
@Data
@Builder
public class ModelListResponse {
    private List<String> models;
    private String model;
}
