package com.xin.test.dto;

/**
 * 文件内容 DTO（二进制内容 + 元数据）
 */
public class FileContentDTO {

    private String fileNo;
    private String fileName;
    private String mimeType;
    private byte[] content;

    public FileContentDTO() {
    }

    public FileContentDTO(String fileNo, String fileName, String mimeType, byte[] content) {
        this.fileNo = fileNo;
        this.fileName = fileName;
        this.mimeType = mimeType;
        this.content = content;
    }

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

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public byte[] getContent() {
        return content;
    }

    public void setContent(byte[] content) {
        this.content = content;
    }

    public long getSizeBytes() {
        return content == null ? 0L : content.length;
    }
}
