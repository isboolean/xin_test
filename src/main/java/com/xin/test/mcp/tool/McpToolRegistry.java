package com.xin.test.mcp.tool;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册中心：收集所有 McpTool Bean，对外提供 tools/list 与按名调用
 */
@Component
public class McpToolRegistry {

    private final Map<String, McpTool> tools = new LinkedHashMap<>();

    public McpToolRegistry(List<McpTool> toolList) {
        for (McpTool tool : toolList) {
            McpTool previous = tools.put(tool.name(), tool);
            if (previous != null) {
                throw new IllegalStateException("MCP 工具名重复: " + tool.name());
            }
        }
    }

    public Collection<McpTool> all() {
        return tools.values();
    }

    public McpTool find(String name) {
        return tools.get(name);
    }

    /**
     * 构造 tools/list 的返回结构
     */
    public List<Map<String, Object>> descriptors() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (McpTool tool : tools.values()) {
            Map<String, Object> descriptor = new LinkedHashMap<>();
            descriptor.put("name", tool.name());
            descriptor.put("title", tool.title());
            descriptor.put("description", tool.description());
            descriptor.put("inputSchema", tool.inputSchema());
            if (tool.outputSchema() != null) {
                descriptor.put("outputSchema", tool.outputSchema());
            }
            if (tool.annotations() != null) {
                descriptor.put("annotations", tool.annotations());
            }
            list.add(descriptor);
        }
        return list;
    }
}
