package com.kama.piagent.service;

import com.kama.piagent.event.PromptEvent;
import com.kama.piagent.model.request.CreateSessionRequest;
import com.kama.piagent.model.request.QueueRequest;
import com.kama.piagent.model.response.CreateSessionResponse;

public interface AgentSessionService {
    CreateSessionResponse create(CreateSessionRequest request);

    void submit(String sessionId, String content);

    void queue(String sessionId, QueueRequest request);

    void abort(String sessionId);

    void reset(String sessionId);

    void run(PromptEvent event);
}
