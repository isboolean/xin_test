package com.xin.test.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.validation.constraints.NotBlank;

/**
 * 文件下载请求 DTO
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class FileDownloadDTO {

    /** 文件编号 */
    @NotBlank(message = "fileNo 不能为空")
    private String fileNo;
}
