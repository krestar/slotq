package com.slotq.mcp;

import java.util.List;

/** One explicit composition root; no annotation/component discovery of tools. */
@FunctionalInterface
public interface McpRegistrations { List<ToolDefinition> tools(); }
