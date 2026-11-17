package com.xin.test.entity;

import java.time.LocalDateTime;

/**
 * 文件记录实体，对应表 t_file_record
 */
public class FileRecordEntity {

    private Long id;
    /** 文件编号 */
    private String fileNo;
    /** 文件名称 */
    private String fileName;
    /** 文件大小（字节） */
    private Long fileSize;
    /** 文件类型 */
    private String contentType;
    /** 创建时间 */
    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    @Override
    public String toString() {
        return "FileRecordEntity{id=" + id + ", fileNo='" + fileNo + "', fileName='" + fileName
                + "', fileSize=" + fileSize + ", contentType='" + contentType + "'}";
    }
}
