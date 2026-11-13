package org.jotaro.jotarocode.service;

import org.jotaro.jotarocode.event.PromptEvent;
import org.jotaro.jotarocode.model.request.CreateSessionRequest;
import org.jotaro.jotarocode.model.request.QueueRequest;
import org.jotaro.jotarocode.model.response.CreateSessionResponse;

import java.util.List;

/**
 * 会话用例。{@code submit} 和 {@code queue} 只发布事件；
 * {@code run} 由异步监听器调用，才会阻塞到这一轮 Agent 结束。
 */
public interface AgentSessionService {
    CreateSessionResponse create(CreateSessionRequest request);

    void submit(String sessionId, String content);

    void queue(String sessionId, QueueRequest request);

    void abort(String sessionId);

    void reset(String sessionId);

    void switchModel(String sessionId, String model);

    List<String> models();

    String defaultModel();

    void run(PromptEvent event);
}
