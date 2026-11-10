package com.kama.piagent.agent;

import com.kama.piagent.agent.runtime.AgentTool;
import com.kama.piagent.agent.runtime.CallbackAgentTool;
import com.kama.piagent.agent.runtime.SpringAiAssistantStreamer;
import com.kama.piagent.agent.runtime.ToolExecutionMode;
import com.kama.piagent.agent.tools.Tool;
import com.kama.piagent.config.ChatClientRegistry;
import com.kama.piagent.config.PiAgentProperties;
import com.kama.piagent.exception.BizException;
import com.kama.piagent.service.ToolFacadeService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Component
public class PiAgentFactory {

    private final ChatClientRegistry chatClientRegistry;
    private final ToolFacadeService toolFacadeService;
    private final PiAgentProperties properties;

    public PiAgentFactory(ChatClientRegistry chatClientRegistry,
                          ToolFacadeService toolFacadeService,
                          PiAgentProperties properties) {
        this.chatClientRegistry = chatClientRegistry;
        this.toolFacadeService = toolFacadeService;
        this.properties = properties;
    }

    public PiAgent create(String model, String systemPrompt) {
        String resolvedModel = StringUtils.hasText(model) ? model : properties.getModel();
        String resolvedPrompt = StringUtils.hasText(systemPrompt) ? systemPrompt : properties.getSystemPrompt();
        ChatClient chatClient = chatClientRegistry.get(resolvedModel);
        if (chatClient == null) {
            throw new BizException("未找到模型: " + resolvedModel);
        }
        return new PiAgent(
                resolvedPrompt,
                adaptTools(toolFacadeService.getAllTools()),
                new SpringAiAssistantStreamer(chatClient),
                properties.getMaxSteps(),
                properties.getMessageWindow(),
                ToolExecutionMode.PARALLEL
        );
    }

    private List<AgentTool> adaptTools(List<Tool> tools) {
        List<AgentTool> agentTools = new ArrayList<>();
        for (Tool tool : tools) {
            ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                    .toolObjects(tool)
                    .build()
                    .getToolCallbacks();
            for (ToolCallback callback : callbacks) {
                agentTools.add(new CallbackAgentTool(callback));
            }
        }
        return agentTools;
    }

    public List<String> modelNames() {
        return chatClientRegistry.keys().stream().sorted().toList();
    }
}
