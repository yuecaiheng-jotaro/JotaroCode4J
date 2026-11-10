package com.kama.piagent.controller;

import com.kama.piagent.model.common.ApiResponse;
import com.kama.piagent.model.request.CreateSessionRequest;
import com.kama.piagent.model.request.PromptRequest;
import com.kama.piagent.model.request.QueueRequest;
import com.kama.piagent.model.response.CreateSessionResponse;
import com.kama.piagent.service.AgentSessionService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class SessionController {

    private final AgentSessionService agentSessionService;

    @PostMapping("/sessions")
    public ApiResponse<CreateSessionResponse> create(@RequestBody(required = false) CreateSessionRequest request) {
        return ApiResponse.success(agentSessionService.create(request));
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
}
