package com.xin.test.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xin.test.mcp.McpServerProperties;
import com.xin.test.mcp.context.McpCallContext;
import com.xin.test.mcp.tool.McpTool;
import com.xin.test.mcp.tool.McpToolRegistry;
import com.xin.test.mcp.tool.McpToolResult;
import com.xin.test.mcp.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 协议处理（JSON-RPC 2.0 over Streamable HTTP）
 * <p>
 * 支持方法：initialize / ping / tools/list / tools/call，以及 notifications/initialized 等通知。
 * 通知（无 id）不返回响应体。
 */
@Component
public class McpProtocolHandler {

    private static final Logger log = LoggerFactory.getLogger(McpProtocolHandler.class);

    private static final List<String> SUPPORTED_VERSIONS = Arrays.asList(
            "2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25");

    private final McpToolRegistry toolRegistry;
    private final McpServerProperties properties;

    public McpProtocolHandler(McpToolRegistry toolRegistry, McpServerProperties properties) {
        this.toolRegistry = toolRegistry;
        this.properties = properties;
    }

    /**
     * 处理请求体，返回响应 JSON；返回 null 表示无需响应体（通知 / 批量通知）
     */
    public String handle(String body, McpCallContext context) {
        if (body == null || body.trim().isEmpty()) {
            return JsonUtils.toJson(errorResponse(null, McpErrorCode.INVALID_REQUEST, "Empty request body"));
        }

        JsonNode root;
        try {
            root = JsonUtils.readTree(body);
        } catch (Exception e) {
            return JsonUtils.toJson(errorResponse(null, McpErrorCode.PARSE_ERROR, "Parse error"));
        }

        if (root.isArray()) {
            return handleBatch((ArrayNode) root, context);
        }
        ObjectNode response = handleSingle(root, context);
        return response == null ? null : JsonUtils.toJson(response);
    }

    private String handleBatch(ArrayNode requests, McpCallContext context) {
        if (requests.size() == 0) {
            return JsonUtils.toJson(errorResponse(null, McpErrorCode.INVALID_REQUEST, "Empty batch"));
        }
        ArrayNode responses = JsonUtils.mapper().createArrayNode();
        for (JsonNode request : requests) {
            ObjectNode response = handleSingle(request, context);
            if (response != null) {
                responses.add(response);
            }
        }
        return responses.size() == 0 ? null : JsonUtils.toJson(responses);
    }

    private ObjectNode handleSingle(JsonNode request, McpCallContext context) {
        JsonNode idNode = request.get("id");
        Object id = idNode == null ? null : JsonUtils.mapper().convertValue(idNode, Object.class);

        if (!request.isObject()) {
            return errorResponse(id, McpErrorCode.INVALID_REQUEST, "Invalid request");
        }
        String method = request.path("method").asText(null);
        if (method == null) {
            return errorResponse(id, McpErrorCode.INVALID_REQUEST, "Missing method");
        }

        boolean notification = !request.has("id");
        try {
            if (notification) {
                handleNotification(method);
                return null;
            }
            Object result = dispatch(method, request.get("params"), context);
            return successResponse(id, result);
        } catch (McpException e) {
            log.warn("MCP 协议错误，method={}, code={}, message={}", method, e.getCode(), e.getMessage());
            return errorResponse(id, e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("MCP 处理异常，method={}", method, e);
            return errorResponse(id, McpErrorCode.INTERNAL_ERROR, "Internal error");
        }
    }

    private void handleNotification(String method) {
        if ("notifications/initialized".equals(method)
                || "notifications/cancelled".equals(method)
                || "notifications/roots/list_changed".equals(method)) {
            log.debug("收到 MCP 通知: {}", method);
            return;
        }
        log.debug("收到未识别的 MCP 通知: {}", method);
    }

    private Object dispatch(String method, JsonNode params, McpCallContext context) {
        if ("initialize".equals(method)) {
            return buildInitializeResult(params);
        }
        if ("ping".equals(method)) {
            return new LinkedHashMap<String, Object>();
        }
        if ("tools/list".equals(method)) {
            return buildToolsListResult();
        }
        if ("tools/call".equals(method)) {
            return handleToolsCall(params);
        }
        throw McpException.methodNotFound(method);
    }

    private Map<String, Object> buildInitializeResult(JsonNode params) {
        String requested = params == null ? null : params.path("protocolVersion").asText(null);
        String protocolVersion = SUPPORTED_VERSIONS.contains(requested)
                ? requested : properties.getProtocolVersion();

        Map<String, Object> toolsCapability = new LinkedHashMap<>();
        toolsCapability.put("listChanged", false);
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", toolsCapability);

        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", properties.getName());
        serverInfo.put("version", properties.getVersion());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", protocolVersion);
        result.put("capabilities", capabilities);
        result.put("serverInfo", serverInfo);
        result.put("instructions", "按 fileId 提供非结构化文件下载能力，供 PDF 识别类 Agent 调用。");
        return result;
    }

    private Map<String, Object> buildToolsListResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", toolRegistry.descriptors());
        return result;
    }

    private Object handleToolsCall(JsonNode params) {
        if (params == null || !params.isObject()) {
            throw McpException.invalidParams("params 不能为空");
        }
        String name = params.path("name").asText(null);
        if (name == null || name.trim().isEmpty()) {
            throw McpException.invalidParams("工具名不能为空");
        }
        McpTool tool = toolRegistry.find(name);
        if (tool == null) {
            throw McpException.invalidParams("未知工具: " + name);
        }

        Map<String, Object> arguments = toArguments(params.get("arguments"));
        McpToolResult toolResult = tool.call(arguments);
        return toolResult.toMap();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toArguments(JsonNode argumentsNode) {
        if (argumentsNode == null || argumentsNode.isNull()) {
            return new LinkedHashMap<>();
        }
        if (!argumentsNode.isObject()) {
            throw McpException.invalidParams("arguments 必须为对象");
        }
        return JsonUtils.mapper().convertValue(argumentsNode, Map.class);
    }

    private ObjectNode successResponse(Object id, Object result) {
        ObjectNode response = JsonUtils.objectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", JsonUtils.mapper().valueToTree(id));
        response.set("result", JsonUtils.mapper().valueToTree(result));
        return response;
    }

    private ObjectNode errorResponse(Object id, int code, String message) {
        ObjectNode response = JsonUtils.objectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", JsonUtils.mapper().valueToTree(id));
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return response;
    }

    /** 便于调试：当前已注册工具名 */
    public List<String> toolNames() {
        List<String> names = new ArrayList<>();
        Iterator<McpTool> iterator = toolRegistry.all().iterator();
        while (iterator.hasNext()) {
            names.add(iterator.next().name());
        }
        return names;
    }
}
