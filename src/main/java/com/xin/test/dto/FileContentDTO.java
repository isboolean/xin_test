package com.xin.test.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 文件内容 DTO（二进制内容 + 元数据）
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class FileContentDTO {

    private String fileNo;
    private String fileName;
    private String mimeType;
    /** 归一化扩展名（不带点），供文件名兜底使用 */
    private String fileType;
    private byte[] content;

    public FileContentDTO(String fileNo, String fileName, String mimeType, byte[] content) {
        this(fileNo, fileName, mimeType, null, content);
    }

    public long getSizeBytes() {
        return content == null ? 0L : content.length;
    }
}
