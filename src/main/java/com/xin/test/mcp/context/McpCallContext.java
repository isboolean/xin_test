package com.xin.test.mcp.context;

/**
 * Agent 平台回调 MCP 时注入的上下文 Header（BE04）
 */
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

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public boolean isEmpty() {
        return tenantId == null && agentId == null && userId == null
                && sessionId == null && runId == null;
    }

    @Override
    public String toString() {
        return "tenantId=" + tenantId + ", agentId=" + agentId + ", userId=" + userId
                + ", sessionId=" + sessionId + ", runId=" + runId;
    }
}
