package com.xin.test.service.impl;

import com.xin.test.mcp.dto.FileContentDTO;
import com.xin.test.mcp.service.McpFileDownloadService;
import com.xin.test.service.FileService;
import org.springframework.stereotype.Service;

/**
 * REST 文件服务实现：下载能力复用 mcp 侧 {@link McpFileDownloadService}，
 * 取数与组装逻辑见 mcp/util、mcp/service 包。
 */
@Service
public class FileServiceImpl implements FileService {

    private final McpFileDownloadService downloadService;

    public FileServiceImpl(McpFileDownloadService downloadService) {
        this.downloadService = downloadService;
    }

    @Override
    public byte[] downloadFile(String fileNo) {
        FileContentDTO detail = downloadService.downloadFileDetail(fileNo);
        return detail == null ? null : detail.getContent();
    }
}
