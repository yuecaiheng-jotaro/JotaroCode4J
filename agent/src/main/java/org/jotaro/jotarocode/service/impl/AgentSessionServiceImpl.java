package org.jotaro.jotarocode.service.impl;

import org.jotaro.jotarocode.agent.JotaroAgent;
import org.jotaro.jotarocode.agent.JotaroAgentFactory;
import org.jotaro.jotarocode.agent.context.ContextManager;
import org.jotaro.jotarocode.agent.runtime.AgentEvent;
import org.jotaro.jotarocode.agent.runtime.AgentEventType;
import org.jotaro.jotarocode.event.PromptEvent;
import org.jotaro.jotarocode.exception.BizException;
import org.jotaro.jotarocode.model.dto.AgentEventView;
import org.jotaro.jotarocode.model.request.CreateSessionRequest;
import org.jotaro.jotarocode.model.request.QueueRequest;
import org.jotaro.jotarocode.model.response.CreateSessionResponse;
import org.jotaro.jotarocode.service.AgentSessionService;
import org.jotaro.jotarocode.service.SseService;
import org.jotaro.jotarocode.skill.SkillCatalog;
import jakarta.annotation.Resource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Web 会话管理服务的内存实现。
 * <p>
 * 核心职责与流转说明：
 * <ul>
 *   <li>会话实例保存在进程内的 {@link ConcurrentHashMap} 中，并带有空闲超时（2 小时）与最大容量（200 个）淘汰机制。</li>
 *   <li>创建会话时，自动在 {@link JotaroAgent} 上注册事件监听器，将内部 {@link AgentEvent} 转换为扁平的 {@link AgentEventView} 并通过 {@link SseService} 推送给前端。</li>
 *   <li>用户提交消息时，若以 {@code /skill:名称} 开头，会在发布 {@link PromptEvent} 前先通过 {@link SkillCatalog#expand(String)} 展开为技能完整正文，再交给异步线程执行。</li>
 * </ul>
 */
@Service
public class AgentSessionServiceImpl implements AgentSessionService {

    /** 空闲会话的最大存活时间（2 小时）。 */
    private static final long SESSION_TTL_MS = Duration.ofHours(2).toMillis();
    /** 内存中保留的最大会话数量上限。 */
    private static final int MAX_SESSIONS = 200;

    /** Agent 实例创建与模型切换工厂。 */
    @Resource
    private JotaroAgentFactory jotaroAgentFactory;
    /** SSE 实时推送服务。 */
    @Resource
    private SseService sseService;
    /** Spring 应用事件发布器，用于将用户消息投递给异步监听器。 */
    @Resource
    private ApplicationEventPublisher eventPublisher;
    /** 技能目录，负责解析并展开 {@code /skill:名称} 命令。 */
    @Resource
    private SkillCatalog skillCatalog;

    /** 活跃会话表：key 为 sessionId，value 为对应的 {@link JotaroAgent} 实例。 */
    private final ConcurrentMap<String, JotaroAgent> sessions = new ConcurrentHashMap<>();
    /** 会话最后访问时间戳表：key 为 sessionId，value 为毫秒时间戳，用于 TTL 与 LRU 清理。 */
    private final ConcurrentMap<String, Long> lastAccess = new ConcurrentHashMap<>();

    /**
     * 创建新会话，绑定 SSE 事件转发回调，并在创建前顺带清理过期或超量的空闲会话。
     */
    @Override
    public CreateSessionResponse create(CreateSessionRequest request) {
        evictExpiredSessions();
        CreateSessionRequest safeRequest = request == null ? new CreateSessionRequest() : request;
        String model = StringUtils.hasText(safeRequest.getModel()) ? safeRequest.getModel() : jotaroAgentFactory.defaultModel();
        String sessionId = UUID.randomUUID().toString();
        JotaroAgent agent = jotaroAgentFactory.create(model, safeRequest.getSystemPrompt());
        // 订阅 Agent 运行期事件，实时转换为前端视图并通过 SSE 推送
        agent.subscribe(event -> sseService.send(sessionId, toView(event)));
        sessions.put(sessionId, agent);
        lastAccess.put(sessionId, System.currentTimeMillis());
        return CreateSessionResponse.builder().sessionId(sessionId).model(model).build();
    }

    /**
     * 提交普通用户消息：校验非空与会话存在后，展开技能指令并发布 {@code followUp=false} 的异步事件。
     */
    @Override
    public void submit(String sessionId, String content) {
        requireContent(content);
        require(sessionId);
        eventPublisher.publishEvent(new PromptEvent(sessionId, expand(content), false));
    }

    /**
     * 在运行中排入消息：根据 {@link QueueRequest#getKind()} 决定是作为 {@code STEER} 还是 {@code FOLLOW_UP} 发布事件。
     */
    @Override
    public void queue(String sessionId, QueueRequest request) {
        if (request == null) {
            throw new BizException("消息内容不能为空");
        }
        requireContent(request.getContent());
        require(sessionId);
        boolean followUp = request.getKind() == QueueRequest.QueueKind.FOLLOW_UP;
        eventPublisher.publishEvent(new PromptEvent(sessionId, expand(request.getContent()), followUp));
    }

    /**
     * 请求中断当前会话正在执行的 Agent 循环；若当前并未运行则抛出业务异常。
     */
    @Override
    public void abort(String sessionId) {
        JotaroAgent agent = require(sessionId);
        if (!agent.isRunning()) {
            throw new BizException("当前会话没有正在运行的 Agent");
        }
        agent.abort();
    }

    /**
     * 重置会话消息上下文；若 Agent 正在运行，底层抛出的 {@link IllegalStateException} 会被转为 {@link BizException}。
     */
    @Override
    public void reset(String sessionId) {
        try {
            require(sessionId).reset();
        } catch (IllegalStateException exception) {
            throw new BizException(exception.getMessage());
        }
    }

    /**
     * 切换指定会话的模型。
     */
    @Override
    public void switchModel(String sessionId, String model) {
        jotaroAgentFactory.switchModel(require(sessionId), model);
    }

    @Override
    public ContextManager.ContextSnapshot inspectContext(String sessionId) {
        return require(sessionId).inspectContext();
    }

    @Override
    public ContextManager.ContextSnapshot compact(String sessionId, Double ratio) {
        JotaroAgent agent = require(sessionId);
        try {
            agent.compact(ratio);
            return agent.inspectContext();
        } catch (IllegalStateException exception) {
            throw new BizException(exception.getMessage());
        }
    }

    /**
     * 返回当前所有可用模型名称。
     */
    @Override
    public java.util.List<String> models() {
        return jotaroAgentFactory.modelNames();
    }

    /**
     * 返回默认模型名称。
     */
    @Override
    public String defaultModel() {
        return jotaroAgentFactory.defaultModel();
    }

    /**
     * 由异步事件监听器调用：根据 {@code followUp} 标志调用 {@link JotaroAgent#followUp(String)} 或 {@link JotaroAgent#prompt(String)}。
     */
    @Override
    public void run(PromptEvent event) {
        JotaroAgent agent = require(event.getSessionId());
        if (event.isFollowUp()) {
            agent.followUp(event.getContent());
        } else {
            agent.prompt(event.getContent());
        }
    }

    /**
     * 将 {@code /skill:名称} 形式的输入展开为技能完整提示词；若技能不存在或内容非法则转为 {@link BizException}。
     */
    private String expand(String content) {
        try {
            return skillCatalog.expand(content);
        } catch (IllegalStateException exception) {
            throw new BizException(exception.getMessage());
        }
    }

    /**
     * 按 sessionId 查找会话并刷新最后访问时间；若会话不存在则抛出 {@link BizException}。
     */
    private JotaroAgent require(String sessionId) {
        JotaroAgent agent = sessions.get(sessionId);
        if (agent == null) {
            throw new BizException("会话不存在: " + sessionId);
        }
        lastAccess.put(sessionId, System.currentTimeMillis());
        return agent;
    }

    /**
     * 清理已超过空闲存活时间（{@link #SESSION_TTL_MS}）且未在运行的会话；
     * 若总会话数仍达到上限（{@link #MAX_SESSIONS}），则额外驱逐最久未访问的一个空闲会话。
     */
    private void evictExpiredSessions() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : lastAccess.entrySet()) {
            String id = entry.getKey();
            JotaroAgent agent = sessions.get(id);
            if (agent == null) {
                lastAccess.remove(id);
                continue;
            }
            // 仅驱逐非运行态且已超时的会话
            if (!agent.isRunning() && now - entry.getValue() > SESSION_TTL_MS) {
                sessions.remove(id);
                lastAccess.remove(id);
            }
        }
        // 容量达到上限时，按最久未访问（LRU）淘汰一个非运行态会话
        if (sessions.size() >= MAX_SESSIONS) {
            lastAccess.entrySet().stream()
                    .filter(e -> {
                        JotaroAgent agent = sessions.get(e.getKey());
                        return agent != null && !agent.isRunning();
                    })
                    .min(Map.Entry.comparingByValue())
                    .ifPresent(oldest -> {
                        sessions.remove(oldest.getKey());
                        lastAccess.remove(oldest.getKey());
                    });
        }
    }

    /**
     * 校验消息文本非空白，否则抛出 {@link BizException}。
     */
    private void requireContent(String content) {
        if (!StringUtils.hasText(content)) {
            throw new BizException("消息内容不能为空");
        }
    }

    /**
     * 将内部运行时 {@link AgentEvent} 转换为前端 SSE 消费的 {@link AgentEventView}。
     * <p>
     * 忽略思考过程（{@code reasoning=true}）的流式增量；当无增量文本时回退读取完整消息正文。
     */
    private AgentEventView toView(AgentEvent event) {
        String text = event.isReasoning() ? null : event.getTextDelta();
        if (!StringUtils.hasText(text) && event.getMessage() != null) {
            text = event.getMessage().getText();
        }
        return AgentEventView.builder()
                .type(event.getType().name())
                .role(roleOf(event.getMessage()))
                .text(text)
                .toolCallId(event.getToolCallId())
                .toolName(event.getToolName())
                .arguments(event.getArguments())
                .result(event.getResult())
                .error(event.getError())
                .done(event.getType() == AgentEventType.AGENT_END)
                .stepLimitReached(event.isStepLimitReached())
                .build();
    }

    /**
     * 将 Spring AI 的 {@link Message} 实例映射为前端可识别的角色字符串。
     */
    private static String roleOf(Message message) {
        if (message instanceof AssistantMessage) {
            return "assistant";
        }
        if (message instanceof UserMessage) {
            return "user";
        }
        if (message instanceof ToolResponseMessage) {
            return "tool";
        }
        if (message instanceof SystemMessage) {
            return "system";
        }
        return null;
    }
}
