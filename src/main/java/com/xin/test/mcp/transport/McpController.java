package com.xin.test.mcp.transport;

import com.xin.test.mcp.McpServerProperties;
import com.xin.test.mcp.context.McpCallContext;
import com.xin.test.mcp.context.McpCallContextHolder;
import com.xin.test.mcp.protocol.McpProtocolHandler;
import com.xin.test.mcp.tool.McpToolRegistry;
import com.xin.test.mcp.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * MCP 传输层（Streamable HTTP）
 * <p>
 * POST /mcp：JSON-RPC 请求入口；GET /mcp：按规范返回 405（不提供服务端主动推送流）。
 * 同时负责 BE04 的平台上下文 Header 透传采集（Agent-Tenant-Id / Agent-Id / Agent-User-Id /
 * Agent-Session-Id / Agent-Run-Id）。
 */
@RestController
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    private final McpProtocolHandler protocolHandler;
    private final McpToolRegistry toolRegistry;
    private final McpServerProperties properties;

    public McpController(McpProtocolHandler protocolHandler,
                         McpToolRegistry toolRegistry,
                         McpServerProperties properties) {
        this.protocolHandler = protocolHandler;
        this.toolRegistry = toolRegistry;
        this.properties = properties;
    }

    @PostMapping(value = "/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> handle(@RequestBody(required = false) String body,
                                         @RequestHeader(value = "Agent-Tenant-Id", required = false) String tenantId,
                                         @RequestHeader(value = "Agent-Id", required = false) String agentId,
                                         @RequestHeader(value = "Agent-User-Id", required = false) String userId,
                                         @RequestHeader(value = "Agent-Session-Id", required = false) String sessionId,
                                         @RequestHeader(value = "Agent-Run-Id", required = false) String runId,
                                         @RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestHeader(value = "X-MCP-Key", required = false) String mcpKey,
                                         @RequestHeader(value = "Mcp-Session-Id", required = false) String mcpSessionId,
                                         HttpServletResponse response) {

        if (!authorized(authorization, mcpKey)) {
            log.warn("MCP 鉴权失败，来源未通过预共享密钥校验");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(errorJson(-32001, "Unauthorized"));
        }

        McpCallContext context = buildContext(tenantId, agentId, userId, sessionId, runId);
        String responseSessionId = mcpSessionId != null && !mcpSessionId.isEmpty()
                ? mcpSessionId : UUID.randomUUID().toString();
        response.setHeader("Mcp-Session-Id", responseSessionId);

        McpCallContextHolder.set(context);
        try {
            String result = protocolHandler.handle(body, context);
            if (result == null) {
                return ResponseEntity.status(HttpStatus.ACCEPTED).build();
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(result);
        } finally {
            McpCallContextHolder.clear();
        }
    }

    /**
     * Streamable HTTP 允许服务端不提供 GET 流
     */
    @GetMapping("/mcp")
    public ResponseEntity<String> stream() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .header(HttpHeaders.ALLOW, "POST")
                .contentType(MediaType.APPLICATION_JSON)
                .body(errorJson(-32000, "SSE stream is not provided, use POST /mcp"));
    }

    /**
     * 健康检查（免鉴权，供运维与平台探活）
     */
    @GetMapping(value = "/mcp/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("server", properties.getName());
        result.put("version", properties.getVersion());
        result.put("transport", "streamable-http");
        return ResponseEntity.ok(JsonUtils.toJson(result));
    }

    /**
     * 调试用：查看已注册工具（非 MCP 协议端点，鉴权策略与 POST /mcp 一致）
     */
    @GetMapping(value = "/mcp/tools", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> tools(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @RequestHeader(value = "X-MCP-Key", required = false) String mcpKey) {
        if (!authorized(authorization, mcpKey)) {
            log.warn("MCP 调试端点鉴权失败，拒绝访问 /mcp/tools");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(errorJson(-32001, "Unauthorized"));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("server", properties.getName());
        result.put("version", properties.getVersion());
        result.put("protocolVersions", new String[]{"2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25"});
        result.put("tools", toolRegistry.descriptors());
        return ResponseEntity.ok(JsonUtils.toJson(result));
    }

    private boolean authorized(String authorization, String mcpKey) {
        String token = properties.getAuthToken();
        if (token == null || token.trim().isEmpty()) {
            return true;
        }
        if (mcpKey != null && token.equals(mcpKey.trim())) {
            return true;
        }
        if (authorization != null) {
            String value = authorization.trim();
            if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
                value = value.substring(7).trim();
            }
            return token.equals(value);
        }
        return false;
    }

    private McpCallContext buildContext(String tenantId, String agentId, String userId,
                                       String sessionId, String runId) {
        McpCallContext context = new McpCallContext();
        context.setTenantId(tenantId);
        context.setAgentId(agentId);
        context.setUserId(userId);
        context.setSessionId(sessionId);
        context.setRunId(runId);
        return context;
    }

    private String errorJson(int code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", null);
        body.put("error", error);
        return JsonUtils.toJson(body);
    }
}
