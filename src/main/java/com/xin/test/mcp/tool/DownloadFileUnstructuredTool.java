package com.xin.test.mcp.tool;

import com.xin.test.mcp.McpServerProperties;
import com.xin.test.mcp.context.McpCallContext;
import com.xin.test.mcp.context.McpCallContextHolder;
import com.xin.test.mcp.dto.FileContentDTO;
import com.xin.test.mcp.service.McpFileDownloadService;
import com.xin.test.mcp.util.MimeTypeResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 工具：download_file_unstructured
 * <p>
 * 按 fileId 调用 mcp 侧下载能力（McpFileDownloadService#downloadFileDetail），把非结构化文件
 * 以 MCP 嵌入式资源（Base64 blob）返回，供 Agent 运行时解码后交给模型读取。
 */
@Component
public class DownloadFileUnstructuredTool implements McpTool {

    private static final Logger log = LoggerFactory.getLogger(DownloadFileUnstructuredTool.class);

    public static final String TOOL_NAME = "download_file_unstructured";

    private final McpFileDownloadService downloadService;
    private final McpServerProperties properties;

    public DownloadFileUnstructuredTool(McpFileDownloadService downloadService, McpServerProperties properties) {
        this.downloadService = downloadService;
        this.properties = properties;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String title() {
        return "下载非结构化文件";
    }

    @Override
    public String description() {
        return "按 fileId 从业务系统非结构化文件服务读取文件（支持 PDF / Word / Excel / 图片 / 文本等），返回文件内容与元数据。"
                + "内容以嵌入式资源返回，mimeType 由文件类型映射得到（未知类型为 application/octet-stream），blob 为 Base64 编码。"
                + "仅处理本次请求指定的文件 ID，不遍历目录、不返回文件列表。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("fileId", property("string",
                "非结构化文件标识（等同业务系统的 fileNo），例如 F20261116001", 1, 128));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", Collections.singletonList("fileId"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Map<String, Object> outputSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("fileId", property("string", "文件标识", null, null));
        props.put("mimeType", property("string", "文件 MIME 类型", null, null));
        props.put("sizeBytes", property("integer", "文件大小（字节）", null, null));
        props.put("fileName", property("string", "文件名称", null, null));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", Arrays.asList("fileId", "mimeType", "sizeBytes"));
        return schema;
    }

    @Override
    public Map<String, Object> annotations() {
        Map<String, Object> annotations = new LinkedHashMap<>();
        annotations.put("title", title());
        annotations.put("readOnlyHint", true);
        annotations.put("destructiveHint", false);
        annotations.put("idempotentHint", true);
        annotations.put("openWorldHint", false);
        return annotations;
    }

    @Override
    public McpToolResult call(Map<String, Object> arguments) {
        McpCallContext context = McpCallContextHolder.get();

        String fileId = arguments == null ? null : asTrimmedString(arguments.get("fileId"));
        if (fileId == null || fileId.isEmpty()) {
            return failure(null, "INVALID_ARGUMENT", "fileId 不能为空", context);
        }
        if (fileId.length() > 128) {
            return failure(fileId, "INVALID_ARGUMENT", "fileId 长度超出限制", context);
        }

        long start = System.currentTimeMillis();
        try {
            FileContentDTO file = downloadService.downloadFileDetail(fileId);
            if (file == null || file.getContent() == null || file.getContent().length == 0) {
                return failure(fileId, "FILE_EMPTY", "文件内容为空，请补传后重试", context);
            }
            if (file.getSizeBytes() > properties.getMaxFileSizeBytes()) {
                return failure(fileId, "FILE_TOO_LARGE",
                        "文件超过大小限制（" + properties.getMaxFileSizeBytes() + " 字节）", context);
            }

            String mimeType = isBlank(file.getMimeType()) ? properties.getDefaultMimeType() : file.getMimeType();
            String fallbackType = isBlank(file.getFileType())
                    ? MimeTypeResolver.DEFAULT_EXTENSION
                    : file.getFileType();
            String fileName = isBlank(file.getFileName())
                    ? fileId + "." + fallbackType
                    : file.getFileName();

            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("fileId", fileId);
            structured.put("mimeType", mimeType);
            structured.put("sizeBytes", file.getSizeBytes());
            structured.put("fileName", fileName);

            McpToolResult result = McpToolResult.success()
                    .embedResource(buildUri(fileId), mimeType, file.getContent())
                    .structuredContent(structured);

            log.info("MCP 下载成功，fileNo={}, size={}B, cost={}ms, context[{}]",
                    fileId, file.getSizeBytes(), System.currentTimeMillis() - start, context);
            return result;
        } catch (Exception e) {
            log.error("MCP 下载失败，fileNo={}, cost={}ms, context[{}]",
                    fileId, System.currentTimeMillis() - start, context, e);
            return failure(fileId, "DOWNLOAD_FAILED", "读取文件失败，请稍后重试或补传文件", context);
        }
    }

    private McpToolResult failure(String fileId, String errorCode, String message, McpCallContext context) {
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("fileId", fileId);
        structured.put("errorCode", errorCode);
        structured.put("message", message);

        log.warn("MCP 下载返回业务错误，fileNo={}, errorCode={}, context[{}]", fileId, errorCode, context);
        return McpToolResult.success()
                .addText(message)
                .structuredContent(structured)
                .error(true);
    }

    private String buildUri(String fileId) {
        String encoded;
        try {
            encoded = URLEncoder.encode(fileId, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            encoded = fileId;
        }
        return "file:///unstructured/" + encoded;
    }

    private static Map<String, Object> property(String type, String description, Integer minLength, Integer maxLength) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", type);
        property.put("description", description);
        if (minLength != null) {
            property.put("minLength", minLength);
        }
        if (maxLength != null) {
            property.put("maxLength", maxLength);
        }
        return property;
    }

    private static String asTrimmedString(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
