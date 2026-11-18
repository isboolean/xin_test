package com.xin.test.mcp.context;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Agent 平台回调 MCP 时注入的上下文 Header（BE04）
 */
@Getter
@Setter
@ToString
public class McpCallContext {

    /** 平台分配的业务租户标识 */
    private String tenantId;
    /** 被调用的 Agent 标识 */
    private String agentId;
    /** 发起本次调用的用户标识 */
    private String userId;
    /** 平台会话标识 */
    private String sessionId;
    /** 平台运行标识 */
    private String runId;

    public boolean isEmpty() {
        return tenantId == null && agentId == null && userId == null
                && sessionId == null && runId == null;
    }
}
