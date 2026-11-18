package com.xin.test.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 线上文件系统接口的原始返回：二进制内容 + 文件类型（如 .pdf） + 文件名。
 * <p>
 * 仅用于承载上游报文，MIME 映射与文件名清洗由 MimeTypeResolver 在服务层完成。
 */
@Getter
@RequiredArgsConstructor
public class RawFileDTO {

    private final byte[] content;
    private final String fileType;
    private final String fileName;
}
