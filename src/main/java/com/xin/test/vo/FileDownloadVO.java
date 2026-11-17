package com.xin.test.vo;

/**
 * 文件下载信息 VO（文件元数据，二进制内容直接由 Controller 以 byte[] 输出）
 */
public class FileDownloadVO {

    /** 文件编号 */
    private String fileNo;
    /** 文件名称 */
    private String fileName;
    /** 文件大小（字节） */
    private Long fileSize;
    /** 文件类型 */
    private String contentType;

    public String getFileNo() {
        return fileNo;
    }

    public void setFileNo(String fileNo) {
        this.fileNo = fileNo;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }
}
