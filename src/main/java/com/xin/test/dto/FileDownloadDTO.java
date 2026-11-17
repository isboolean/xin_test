package com.xin.test.dto;

import javax.validation.constraints.NotBlank;

/**
 * 文件下载请求 DTO
 */
public class FileDownloadDTO {

    /** 文件编号 */
    @NotBlank(message = "fileNo 不能为空")
    private String fileNo;

    public FileDownloadDTO() {
    }

    public FileDownloadDTO(String fileNo) {
        this.fileNo = fileNo;
    }

    public String getFileNo() {
        return fileNo;
    }

    public void setFileNo(String fileNo) {
        this.fileNo = fileNo;
    }
}
