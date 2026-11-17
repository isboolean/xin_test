package com.xin.test.controller;

import com.xin.test.dto.FileDownloadDTO;
import com.xin.test.service.FileService;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.constraints.NotBlank;

/**
 * 文件接口
 */
@RestController
@RequestMapping("/api/file")
@Validated
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    /**
     * 文件下载接口：接收 fileNo 参数，返回文件二进制内容
     */
    @GetMapping(value = "/download", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public byte[] download(@RequestParam("fileNo") @NotBlank String fileNo) {
        FileDownloadDTO dto = new FileDownloadDTO(fileNo);
        return fileService.downloadFile(dto.getFileNo());
    }
}
