package com.xin.test.util;

import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 文件类型工具：把线上文件系统接口返回的 fileType / fileName 归一化为
 * MIME 类型、标准扩展名与安全文件名。
 */
public final class MimeTypeResolver {

    public static final String DEFAULT_MIME_TYPE = "application/octet-stream";
    public static final String DEFAULT_EXTENSION = "bin";

    private static final int MAX_FILE_NAME_LENGTH = 200;
    private static final String EXT_PATTERN = "[a-z0-9]{1,10}";

    private static final Map<String, String> ALLOWED;

    static {
        Map<String, String> m = new HashMap<>();
        m.put("pdf", "application/pdf");
        m.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        m.put("doc", "application/msword");
        m.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        m.put("xls", "application/vnd.ms-excel");
        m.put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        m.put("ppt", "application/vnd.ms-powerpoint");
        m.put("txt", "text/plain;charset=UTF-8");
        m.put("csv", "text/csv;charset=UTF-8");
        m.put("json", "application/json;charset=UTF-8");
        m.put("xml", "application/xml;charset=UTF-8");
        m.put("png", "image/png");
        m.put("jpg", "image/jpeg");
        m.put("jpeg", "image/jpeg");
        m.put("gif", "image/gif");
        m.put("zip", "application/zip");
        ALLOWED = Collections.unmodifiableMap(m);
    }

    private MimeTypeResolver() {
    }

    /**
     * 解析 MIME：白名单优先，未命中交给 Spring MediaTypeFactory 兜底，
     * 仍无法识别或无扩展名时降级为 application/octet-stream。
     */
    public static String resolve(String fileType, String fileName) {
        String ext = normalizeExtension(fileType, fileName);
        if (DEFAULT_EXTENSION.equals(ext)) {
            return DEFAULT_MIME_TYPE;
        }
        String mime = ALLOWED.get(ext);
        if (mime != null) {
            return mime;
        }
        return MediaTypeFactory.getMediaType("file." + ext)
                .map(MediaType::toString)
                .orElse(DEFAULT_MIME_TYPE);
    }

    /** 归一化扩展名（不带点、小写）；取不到时返回 bin。 */
    public static String normalizeExtension(String fileType, String fileName) {
        String ext = extensionOf(fileType);
        if (ext.isEmpty() && fileName != null) {
            int dot = fileName.lastIndexOf('.');
            if (dot >= 0) {
                ext = extensionOf(fileName.substring(dot));
            }
        }
        return ext.isEmpty() ? DEFAULT_EXTENSION : ext;
    }

    /** 从 fileType 取扩展名：兼容 "pdf" / ".PDF" / 带路径的值。 */
    public static String extensionOf(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }
        String v = value.trim().toLowerCase(Locale.ROOT).replace('\\', '/');
        boolean hadPath = v.indexOf('/') >= 0;
        int slash = v.lastIndexOf('/');
        if (slash >= 0) {
            v = v.substring(slash + 1);
        }
        int dot = v.lastIndexOf('.');
        String ext;
        if (dot >= 0) {
            ext = v.substring(dot + 1);
        } else {
            // 无点时仅接受「纯扩展名」形式（pdf / .pdf），含路径的值一律拒绝
            ext = hadPath ? "" : v;
        }
        return ext.matches(EXT_PATTERN) ? ext : "";
    }

    /**
     * 生成安全文件名：取 basename、过滤控制字符与非法字符、限制长度；
     * 为空时按扩展名拼出 fallbackId.ext。
     */
    public static String safeFileName(String fileName, String fileType, String fallbackId) {
        String name = basename(fileName);
        if (name.isEmpty()) {
            String ext = normalizeExtension(fileType, null);
            name = (fallbackId == null ? "file" : fallbackId) + "." + ext;
        }
        return name;
    }

    private static String basename(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }
        String v = value.trim().replace('\\', '/');
        int slash = v.lastIndexOf('/');
        String name = slash >= 0 ? v.substring(slash + 1) : v;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                continue;
            }
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|') {
                continue;
            }
            sb.append(c);
        }
        String cleaned = sb.toString().trim();
        if (cleaned.isEmpty() || cleaned.startsWith("..")) {
            return "";
        }
        return cleaned.length() > MAX_FILE_NAME_LENGTH
                ? cleaned.substring(0, MAX_FILE_NAME_LENGTH)
                : cleaned;
    }
}
