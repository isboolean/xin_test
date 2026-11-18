package com.xin.test.mcp.tool;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tools/call 的返回体
 * <p>
 * 结构遵循 MCP 规范：content[] + structuredContent + isError
 */
@Getter
public class McpToolResult {

    private final List<Map<String, Object>> content = new ArrayList<>();
    private Map<String, Object> structuredContent;
    private boolean error;

    public static McpToolResult success() {
        return new McpToolResult();
    }

    /**
     * 追加嵌入式资源内容（文件字节以 Base64 blob 返回）
     */
    public McpToolResult embedResource(String uri, String mimeType, byte[] data) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("uri", uri);
        resource.put("mimeType", mimeType);
        resource.put("blob", Base64.getEncoder().encodeToString(data));

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "resource");
        item.put("resource", resource);
        content.add(item);
        return this;
    }

    /**
     * 追加纯文本内容（错误说明、提示信息）
     */
    public McpToolResult addText(String text) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "text");
        item.put("text", text);
        content.add(item);
        return this;
    }

    public McpToolResult structuredContent(Map<String, Object> structured) {
        this.structuredContent = structured;
        return this;
    }

    public McpToolResult error(boolean error) {
        this.error = error;
        return this;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        if (structuredContent != null) {
            result.put("structuredContent", structuredContent);
        }
        result.put("isError", error);
        return result;
    }
}
