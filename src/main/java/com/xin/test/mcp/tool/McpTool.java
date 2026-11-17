package com.xin.test.mcp.tool;

import java.util.Map;

/**
 * 业务 MCP 工具契约，实现类注册为 Spring Bean 即自动暴露到 tools/list
 */
public interface McpTool {

    /** 工具名（唯一） */
    String name();

    /** 展示名称 */
    String title();

    /** 工具说明，供 Agent/LLM 判断何时调用 */
    String description();

    /** 入参 JSON Schema */
    Map<String, Object> inputSchema();

    /** 出参 JSON Schema，可为 null */
    default Map<String, Object> outputSchema() {
        return null;
    }

    /** 工具注解（readOnlyHint 等），可为 null */
    default Map<String, Object> annotations() {
        return null;
    }

    /**
     * 执行工具
     *
     * @param arguments 入参（已通过 JSON Schema 的基础校验）
     */
    McpToolResult call(Map<String, Object> arguments);
}
