package com.xin.test.vo;

import lombok.Getter;
import lombok.Setter;

/**
 * 文件下载信息 VO（文件元数据，二进制内容直接由 Controller 以 byte[] 输出）
 */
@Getter
@Setter
public class FileDownloadVO {

    /** 文件编号 */
    private String fileNo;
    /** 文件名称 */
    private String fileName;
    /** 文件大小（字节） */
    private Long fileSize;
    /** 文件类型 */
    private String contentType;
}
