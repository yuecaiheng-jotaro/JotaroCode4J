package org.jotaro.jotarocode.controller;

import org.jotaro.jotarocode.model.common.ApiResponse;
import org.jotaro.jotarocode.model.request.CreateSessionRequest;
import org.jotaro.jotarocode.model.request.PromptRequest;
import org.jotaro.jotarocode.model.request.QueueRequest;
import org.jotaro.jotarocode.model.response.CreateSessionResponse;
import org.jotaro.jotarocode.model.response.ModelListResponse;
import org.jotaro.jotarocode.model.response.SkillView;
import org.jotaro.jotarocode.service.AgentSessionService;
import org.jotaro.jotarocode.skill.SkillCatalog;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话相关的 JSON 接口。发消息只是入队，接口马上返回；
 * 页面要先连上 {@code /events}，否则这段时间产生的事件没有接收方。
 */
@RestController
@RequestMapping("/api")
public class SessionController {

    @Resource
    private AgentSessionService agentSessionService;

    @Resource
    private SkillCatalog skillCatalog;

    @PostMapping("/sessions")
    public ApiResponse<CreateSessionResponse> create(@RequestBody(required = false) CreateSessionRequest request) {
        return ApiResponse.success(agentSessionService.create(request));
    }

    @GetMapping("/models")
    public ApiResponse<ModelListResponse> models() {
        return ApiResponse.success(ModelListResponse.builder()
                .models(agentSessionService.models())
                .model(agentSessionService.defaultModel())
                .build());
    }

    @GetMapping("/skills")
    public ApiResponse<List<SkillView>> skills() {
        return ApiResponse.success(skillCatalog.skills().stream()
                .map(skill -> SkillView.builder()
                        .name(skill.name())
                        .description(skill.description())
                        .commandOnly(skill.disableModelInvocation())
                        .build())
                .toList());
    }

    @PostMapping("/sessions/{sessionId}/messages")
    public ApiResponse<Void> prompt(@PathVariable String sessionId, @RequestBody PromptRequest request) {
        agentSessionService.submit(sessionId, request == null ? null : request.getContent());
        return ApiResponse.success();
    }

    @PostMapping("/sessions/{sessionId}/queue")
    public ApiResponse<Void> queue(@PathVariable String sessionId, @RequestBody QueueRequest request) {
        agentSessionService.queue(sessionId, request);
        return ApiResponse.success();
    }

    @PostMapping("/sessions/{sessionId}/abort")
    public ApiResponse<Void> abort(@PathVariable String sessionId) {
        agentSessionService.abort(sessionId);
        return ApiResponse.success();
    }

    @PostMapping("/sessions/{sessionId}/reset")
    public ApiResponse<Void> reset(@PathVariable String sessionId) {
        agentSessionService.reset(sessionId);
        return ApiResponse.success();
    }

    @PostMapping("/sessions/{sessionId}/model")
    public ApiResponse<Void> switchModel(@PathVariable String sessionId, @RequestBody CreateSessionRequest request) {
        agentSessionService.switchModel(sessionId, request == null ? null : request.getModel());
        return ApiResponse.success();
    }
}
