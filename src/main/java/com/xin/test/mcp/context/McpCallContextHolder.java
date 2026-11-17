package com.xin.test.mcp.context;

/**
 * 当前请求的 MCP 调用上下文（ThreadLocal 保存，工具执行时读取）
 */
public final class McpCallContextHolder {

    private static final ThreadLocal<McpCallContext> HOLDER = new ThreadLocal<>();

    private McpCallContextHolder() {
    }

    public static void set(McpCallContext context) {
        HOLDER.set(context);
    }

    public static McpCallContext get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
