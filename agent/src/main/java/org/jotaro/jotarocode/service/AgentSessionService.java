package org.jotaro.jotarocode.service;

import org.jotaro.jotarocode.event.PromptEvent;
import org.jotaro.jotarocode.model.request.CreateSessionRequest;
import org.jotaro.jotarocode.model.request.QueueRequest;
import org.jotaro.jotarocode.model.response.CreateSessionResponse;

import java.util.List;

/**
 * Web 会话管理与消息调度服务接口。
 * <p>
 * 负责维护会话生命周期、模型切换、技能展开以及异步消息流转：
 * {@link #submit(String, String)} 和 {@link #queue(String, QueueRequest)} 在 HTTP 线程中仅完成校验与事件发布；
 * 真正的 Agent 循环由异步监听器调用 {@link #run(PromptEvent)} 在后台线程中执行，并通过 {@link SseService} 推送实时事件。
 */
public interface AgentSessionService {
    /**
     * 创建一个新的 Agent 会话并注册 SSE 事件转发监听。
     *
     * @param request 创建会话参数（包含可选模型名与系统提示词）
     * @return 包含新会话 ID 与实际生效模型名的响应
     */
    CreateSessionResponse create(CreateSessionRequest request);

    /**
     * 向指定会话提交一条用户消息（空闲时启动新一轮，运行中作为 steering 插入当前轮）。
     *
     * @param sessionId 会话 ID
     * @param content   用户输入内容（支持 {@code /skill:名称} 前缀展开）
     */
    void submit(String sessionId, String content);

    /**
     * 在会话运行期间按指定队列模式（{@code STEER} 或 {@code FOLLOW_UP}）排入一条用户消息。
     *
     * @param sessionId 会话 ID
     * @param request   队列消息请求
     */
    void queue(String sessionId, QueueRequest request);

    /**
     * 中断指定会话正在运行的 Agent 任务。
     *
     * @param sessionId 会话 ID
     */
    void abort(String sessionId);

    /**
     * 清空指定会话的对话历史（运行中调用会抛出业务异常）。
     *
     * @param sessionId 会话 ID
     */
    void reset(String sessionId);

    /**
     * 切换指定会话使用的聊天模型。
     *
     * @param sessionId 会话 ID
     * @param model     目标模型名称
     */
    void switchModel(String sessionId, String model);

    /**
     * 获取所有已启用（配置了有效 API Key）的模型名称列表。
     *
     * @return 模型名称列表
     */
    List<String> models();

    /**
     * 获取当前默认选用的模型名称。
     *
     * @return 默认模型名称
     */
    String defaultModel();

    /**
     * 在异步线程中实际驱动指定会话的 Agent 处理消息事件，直到本轮结束。
     *
     * @param event 待执行的消息事件
     */
    void run(PromptEvent event);
}
