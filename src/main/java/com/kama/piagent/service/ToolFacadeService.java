package com.kama.piagent.service;

import com.kama.piagent.agent.tools.Tool;

import java.util.List;

public interface ToolFacadeService {
    List<Tool> getAllTools();

    List<Tool> getFixedTools();

    List<Tool> getOptionalTools();
}
