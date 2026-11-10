package com.kama.piagent.service.impl;

import com.kama.piagent.agent.tools.Tool;
import com.kama.piagent.agent.tools.ToolType;
import com.kama.piagent.service.ToolFacadeService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@AllArgsConstructor
public class ToolFacadeServiceImpl implements ToolFacadeService {

    private final List<Tool> tools;

    @Override
    public List<Tool> getAllTools() {
        return tools;
    }

    @Override
    public List<Tool> getFixedTools() {
        return tools.stream().filter(tool -> tool.getType() == ToolType.FIXED).toList();
    }

    @Override
    public List<Tool> getOptionalTools() {
        return tools.stream().filter(tool -> tool.getType() == ToolType.OPTIONAL).toList();
    }
}
