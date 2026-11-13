package org.jotaro.jotarocode.service.impl;

import org.jotaro.jotarocode.agent.JotaroAgent;
import org.jotaro.jotarocode.agent.JotaroAgentFactory;
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

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 会话存在本进程的 Map 里。创建时订阅 Agent 事件并转成前端视图。
 * {@code /skill:名称} 在发布事件前展开成技能正文，页面上仍显示用户原来输入的那一行。
 */
@Service
public class AgentSessionServiceImpl implements AgentSessionService {

    @Resource
    private JotaroAgentFactory jotaroAgentFactory;
    @Resource
    private SseService sseService;
    @Resource
    private ApplicationEventPublisher eventPublisher;
    @Resource
    private SkillCatalog skillCatalog;
    private final ConcurrentMap<String, JotaroAgent> sessions = new ConcurrentHashMap<>();

    @Override
    public CreateSessionResponse create(CreateSessionRequest request) {
        CreateSessionRequest safeRequest = request == null ? new CreateSessionRequest() : request;
        String model = StringUtils.hasText(safeRequest.getModel()) ? safeRequest.getModel() : jotaroAgentFactory.defaultModel();
        String sessionId = UUID.randomUUID().toString();
        JotaroAgent agent = jotaroAgentFactory.create(model, safeRequest.getSystemPrompt());
        agent.subscribe(event -> sseService.send(sessionId, toView(event)));
        sessions.put(sessionId, agent);
        return CreateSessionResponse.builder().sessionId(sessionId).model(model).build();
    }

    @Override
    public void submit(String sessionId, String content) {
        requireContent(content);
        require(sessionId);
        eventPublisher.publishEvent(new PromptEvent(sessionId, expand(content), false));
    }

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

    @Override
    public void abort(String sessionId) {
        JotaroAgent agent = require(sessionId);
        if (!agent.isRunning()) {
            throw new BizException("当前会话没有正在运行的 Agent");
        }
        agent.abort();
    }

    @Override
    public void reset(String sessionId) {
        try {
            require(sessionId).reset();
        } catch (IllegalStateException exception) {
            throw new BizException(exception.getMessage());
        }
    }

    @Override
    public void switchModel(String sessionId, String model) {
        jotaroAgentFactory.switchModel(require(sessionId), model);
    }

    @Override
    public java.util.List<String> models() {
        return jotaroAgentFactory.modelNames();
    }

    @Override
    public String defaultModel() {
        return jotaroAgentFactory.defaultModel();
    }

    @Override
    public void run(PromptEvent event) {
        JotaroAgent agent = require(event.getSessionId());
        if (event.isFollowUp()) {
            agent.followUp(event.getContent());
        } else {
            agent.prompt(event.getContent());
        }
    }

    private String expand(String content) {
        try {
            return skillCatalog.expand(content);
        } catch (IllegalStateException exception) {
            throw new BizException(exception.getMessage());
        }
    }

    private JotaroAgent require(String sessionId) {
        JotaroAgent agent = sessions.get(sessionId);
        if (agent == null) {
            throw new BizException("会话不存在: " + sessionId);
        }
        return agent;
    }

    private void requireContent(String content) {
        if (!StringUtils.hasText(content)) {
            throw new BizException("消息内容不能为空");
        }
    }

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
