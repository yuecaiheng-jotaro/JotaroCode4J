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
 * Web 会话与元数据 REST 控制器。
 * <p>
 * 提供创建会话、查询可用模型与技能列表、发送/排队用户消息、中断任务、重置会话及切换模型等 JSON 接口。
 * 注意：发送消息（{@link #prompt} / {@link #queue}）仅做校验并发布异步事件后立即返回，
 * 前端页面必须在发消息前先通过 {@link SseController#events(String)} 建立 {@code /events} SSE 连接，
 * 否则异步执行初期产生的流式事件将因没有接收方而被丢弃。
 */
@RestController
@RequestMapping("/api")
public class SessionController {

    /** 会话管理与消息调度服务。 */
    @Resource
    private AgentSessionService agentSessionService;

    /** 本地技能目录，用于向前端提供可用技能列表。 */
    @Resource
    private SkillCatalog skillCatalog;

    /**
     * 创建一个新的 Agent 会话。
     *
     * @param request 可选的模型名与自定义系统提示词（为空时使用默认配置）
     * @return 新建会话的 ID 与实际选用的模型名称
     */
    @PostMapping("/sessions")
    public ApiResponse<CreateSessionResponse> create(@RequestBody(required = false) CreateSessionRequest request) {
        return ApiResponse.success(agentSessionService.create(request));
    }

    /**
     * 查询当前已配置有效 API Key 的可用模型列表及默认模型。
     *
     * @return 模型列表与默认模型响应
     */
    @GetMapping("/models")
    public ApiResponse<ModelListResponse> models() {
        return ApiResponse.success(ModelListResponse.builder()
                .models(agentSessionService.models())
                .model(agentSessionService.defaultModel())
                .build());
    }

    /**
     * 查询所有已加载的技能摘要列表，供前端展示或斜杠命令提示。
     *
     * @return 技能视图列表
     */
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

    /**
     * 向指定会话提交一条用户消息。
     * <p>
     * 若会话当前空闲则异步启动新一轮 Agent 循环；若正在运行则作为 steering 消息插入当前轮。
     *
     * @param sessionId 会话 ID
     * @param request   包含消息正文的请求体
     * @return 空成功响应（实际输出通过 SSE 推送）
     */
    @PostMapping("/sessions/{sessionId}/messages")
    public ApiResponse<Void> prompt(@PathVariable String sessionId, @RequestBody PromptRequest request) {
        agentSessionService.submit(sessionId, request == null ? null : request.getContent());
        return ApiResponse.success();
    }

    /**
     * 在会话运行期间显式按指定队列类型（{@code STEER} 或 {@code FOLLOW_UP}）排入一条消息。
     *
     * @param sessionId 会话 ID
     * @param request   包含消息内容和队列类型（立即穿插或本轮末尾追加）的请求体
     * @return 空成功响应
     */
    @PostMapping("/sessions/{sessionId}/queue")
    public ApiResponse<Void> queue(@PathVariable String sessionId, @RequestBody QueueRequest request) {
        agentSessionService.queue(sessionId, request);
        return ApiResponse.success();
    }

    /**
     * 中断指定会话正在运行的 Agent 任务。
     *
     * @param sessionId 会话 ID
     * @return 空成功响应
     */
    @PostMapping("/sessions/{sessionId}/abort")
    public ApiResponse<Void> abort(@PathVariable String sessionId) {
        agentSessionService.abort(sessionId);
        return ApiResponse.success();
    }

    /**
     * 清空指定会话的上下文历史记录（仅允许在非运行状态下调用）。
     *
     * @param sessionId 会话 ID
     * @return 空成功响应
     */
    @PostMapping("/sessions/{sessionId}/reset")
    public ApiResponse<Void> reset(@PathVariable String sessionId) {
        agentSessionService.reset(sessionId);
        return ApiResponse.success();
    }

    /**
     * 切换指定会话所使用的底层聊天模型。
     *
     * @param sessionId 会话 ID
     * @param request   包含目标模型名称的请求体
     * @return 空成功响应
     */
    @PostMapping("/sessions/{sessionId}/model")
    public ApiResponse<Void> switchModel(@PathVariable String sessionId, @RequestBody CreateSessionRequest request) {
        agentSessionService.switchModel(sessionId, request == null ? null : request.getModel());
        return ApiResponse.success();
    }
}
