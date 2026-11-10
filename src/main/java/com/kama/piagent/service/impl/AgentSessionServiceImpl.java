package com.kama.piagent.service.impl;

import com.kama.piagent.agent.PiAgent;
import com.kama.piagent.agent.PiAgentFactory;
import com.kama.piagent.agent.runtime.AgentEvent;
import com.kama.piagent.agent.runtime.AgentEventType;
import com.kama.piagent.event.PromptEvent;
import com.kama.piagent.exception.BizException;
import com.kama.piagent.model.dto.AgentEventView;
import com.kama.piagent.model.request.CreateSessionRequest;
import com.kama.piagent.model.request.QueueRequest;
import com.kama.piagent.model.response.CreateSessionResponse;
import com.kama.piagent.service.AgentSessionService;
import com.kama.piagent.service.SseService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@RequiredArgsConstructor
public class AgentSessionServiceImpl implements AgentSessionService {

    private final PiAgentFactory piAgentFactory;
    private final SseService sseService;
    private final ApplicationEventPublisher eventPublisher;
    private final ConcurrentMap<String, PiAgent> sessions = new ConcurrentHashMap<>();

    @Override
    public CreateSessionResponse create(CreateSessionRequest request) {
        CreateSessionRequest safeRequest = request == null ? new CreateSessionRequest() : request;
        String sessionId = UUID.randomUUID().toString();
        PiAgent agent = piAgentFactory.create(safeRequest.getModel(), safeRequest.getSystemPrompt());
        agent.subscribe(event -> sseService.send(sessionId, toView(event)));
        sessions.put(sessionId, agent);
        return CreateSessionResponse.builder().sessionId(sessionId).build();
    }

    @Override
    public void submit(String sessionId, String content) {
        requireContent(content);
        require(sessionId);
        eventPublisher.publishEvent(new PromptEvent(sessionId, content, false));
    }

    @Override
    public void queue(String sessionId, QueueRequest request) {
        if (request == null) {
            throw new BizException("消息内容不能为空");
        }
        requireContent(request.getContent());
        require(sessionId);
        boolean followUp = request.getKind() == QueueRequest.QueueKind.FOLLOW_UP;
        eventPublisher.publishEvent(new PromptEvent(sessionId, request.getContent(), followUp));
    }

    @Override
    public void abort(String sessionId) {
        PiAgent agent = require(sessionId);
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
    public void run(PromptEvent event) {
        PiAgent agent = require(event.getSessionId());
        if (event.isFollowUp()) {
            agent.followUp(event.getContent());
        } else {
            agent.prompt(event.getContent());
        }
    }

    private PiAgent require(String sessionId) {
        PiAgent agent = sessions.get(sessionId);
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
        String text = event.getTextDelta();
        if (!StringUtils.hasText(text) && event.getMessage() != null) {
            text = event.getMessage().getText();
        }
        return AgentEventView.builder()
                .type(event.getType().name())
                .text(text)
                .toolName(event.getToolName())
                .arguments(event.getArguments())
                .result(event.getResult())
                .error(event.getError())
                .done(event.getType() == AgentEventType.AGENT_END)
                .build();
    }
}
