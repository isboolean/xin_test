package com.xin.test.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MCP 服务端配置（前缀 mcp.server）
 */
@Component
@ConfigurationProperties(prefix = "mcp.server")
public class McpServerProperties {

    /** 服务名称，initialize 时返回给客户端 */
    private String name = "xin-test-mcp";
    /** 服务版本 */
    private String version = "1.0.0";
    /** 支持的最新协议版本 */
    private String protocolVersion = "2025-06-18";
    /** 传输层鉴权令牌，为空表示不校验（建议生产使用预共享密钥或网络隔离） */
    private String authToken = "";
    /** 单文件大小上限（字节），默认 10MB */
    private long maxFileSizeBytes = 10L * 1024 * 1024;
    /** 默认 MIME 类型 */
    private String defaultMimeType = "application/pdf";

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public String getAuthToken() {
        return authToken;
    }

    public void setAuthToken(String authToken) {
        this.authToken = authToken;
    }

    public long getMaxFileSizeBytes() {
        return maxFileSizeBytes;
    }

    public void setMaxFileSizeBytes(long maxFileSizeBytes) {
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public String getDefaultMimeType() {
        return defaultMimeType;
    }

    public void setDefaultMimeType(String defaultMimeType) {
        this.defaultMimeType = defaultMimeType;
    }
}
