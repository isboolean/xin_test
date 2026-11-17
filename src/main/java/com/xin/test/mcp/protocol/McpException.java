package com.xin.test.mcp.protocol;

/**
 * 协议层异常，对应 JSON-RPC error 对象
 */
public class McpException extends RuntimeException {

    private final int code;

    public McpException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static McpException invalidParams(String message) {
        return new McpException(McpErrorCode.INVALID_PARAMS, message);
    }

    public static McpException methodNotFound(String method) {
        return new McpException(McpErrorCode.METHOD_NOT_FOUND, "Method not found: " + method);
    }

    public static McpException internalError(String message) {
        return new McpException(McpErrorCode.INTERNAL_ERROR, message);
    }
}
