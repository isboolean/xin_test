package com.xin.test.mcp.tool;

import com.xin.test.mcp.context.McpCallContext;
import com.xin.test.mcp.context.McpCallContextHolder;
import com.xin.test.mcp.service.OcrRecognizeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具：ocr_recognize
 * <p>
 * 按 fileId 调用 {@link OcrRecognizeService#recognize(String)} 得到 {@code List<String>}，
 * 以 {@code structuredContent.texts} 暴露给大模型，同时以纯文本 content 给出可读内容。
 * <p>
 * 无论成功、入参非法、文件为空还是识别异常，{@code structuredContent.texts}
 * 恒为一个字符串数组（失败时为空数组 {@code []}），不存在 null 或缺失。
 */
@Component
public class OcrRecognizeTool implements McpTool {

    private static final Logger log = LoggerFactory.getLogger(OcrRecognizeTool.class);

    public static final String TOOL_NAME = "ocr_recognize";

    private final OcrRecognizeService ocrService;

    public OcrRecognizeTool(OcrRecognizeService ocrService) {
        this.ocrService = ocrService;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String title() {
        return "OCR 文件内容识别";
    }

    @Override
    public String description() {
        return "按 fileId 读取文件字节与 MIME 类型并调用 OCR 识别，返回整理后的文本行列表（List<String>）。"
                + "structuredContent.texts 恒为字符串数组：入参非法、文件为空或识别异常时返回空数组 []。"
                + "仅处理本次请求指定的文件 ID，不遍历目录、不返回文件列表。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("fileId", property("string",
                "非结构化文件标识（等同业务系统的 fileNo），例如 F20261116001",
                1, OcrRecognizeService.MAX_FILE_ID_LENGTH));

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
        props.put("texts", stringArrayProperty("OCR 识别结果文本行，恒为字符串数组"));
        props.put("count", property("integer", "识别结果行数", null, null));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", Arrays.asList("fileId", "texts", "count"));
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
        if (fileId.length() > OcrRecognizeService.MAX_FILE_ID_LENGTH) {
            return failure(fileId, "INVALID_ARGUMENT", "fileId 长度超出限制", context);
        }

        long start = System.currentTimeMillis();
        try {
            // 核心工具方法恒返回 List<String>；此处再做一次防御性兜底
            List<String> texts = ocrService.recognize(fileId);
            if (texts == null) {
                texts = new ArrayList<>();
            }

            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("fileId", fileId);
            structured.put("texts", texts);
            structured.put("count", texts.size());

            McpToolResult result = McpToolResult.success()
                    .addText(texts.isEmpty() ? "未识别到内容" : String.join("\n", texts))
                    .structuredContent(structured);

            if (texts.isEmpty()) {
                structured.put("errorCode", "TEXT_EMPTY");
                structured.put("message", "未识别到内容，请确认文件后重试");
                result.error(true);
                log.warn("MCP OCR 返回空结果，fileNo={}, cost={}ms, context[{}]",
                        fileId, System.currentTimeMillis() - start, context);
            } else {
                log.info("MCP OCR 成功，fileNo={}, lines={}, cost={}ms, context[{}]",
                        fileId, texts.size(), System.currentTimeMillis() - start, context);
            }
            return result;
        } catch (Exception e) {
            log.error("MCP OCR 失败，fileNo={}, cost={}ms, context[{}]",
                    fileId, System.currentTimeMillis() - start, context, e);
            return failure(fileId, "OCR_FAILED", "识别失败，请稍后重试", context);
        }
    }

    /** 失败路径同样保证 texts 为 List<String>（空数组）。 */
    private McpToolResult failure(String fileId, String errorCode, String message, McpCallContext context) {
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("fileId", fileId);
        structured.put("texts", new ArrayList<String>());
        structured.put("count", 0);
        structured.put("errorCode", errorCode);
        structured.put("message", message);

        log.warn("MCP OCR 返回业务错误，fileNo={}, errorCode={}, context[{}]", fileId, errorCode, context);
        return McpToolResult.success()
                .addText(message)
                .structuredContent(structured)
                .error(true);
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

    private static Map<String, Object> stringArrayProperty(String description) {
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("type", "string");
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "array");
        property.put("description", description);
        property.put("items", items);
        return property;
    }

    private static String asTrimmedString(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }
}
