package com.xin.test.mcp.service;

import com.xin.test.mcp.dto.FileContentDTO;
import com.xin.test.mcp.dto.RawFileDTO;
import com.xin.test.mcp.util.MimeTypeResolver;
import com.xin.test.mcp.util.RawFileFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * MCP 文件下载服务：按 fileNo 从上游取数并组装为 {@link FileContentDTO}。
 * <p>
 * 上游取数逻辑集中在 {@link RawFileFetcher#fetchRawFile(String)}（线上替换点），
 * 组装逻辑在 {@link #assemble(String, RawFileDTO)}（包可见静态方法，便于单测）。
 */
@Service
public class McpFileDownloadService {

    private static final Logger log = LoggerFactory.getLogger(McpFileDownloadService.class);

    /**
     * 按文件编号获取文件内容及元数据（二进制 + 名称 + MIME + 大小）。
     *
     * @param fileNo 文件编号
     * @return 文件内容与元数据
     */
    public FileContentDTO downloadFileDetail(String fileNo) {
        FileContentDTO dto = assemble(fileNo, RawFileFetcher.fetchRawFile(fileNo));
        log.info("文件下载，fileNo={}, fileName={}, mimeType={}, size={}B",
                fileNo, dto.getFileName(), dto.getMimeType(), dto.getSizeBytes());
        return dto;
    }

    /** 组装逻辑与上游取数解耦，便于用任意 byte[] + fileType + fileName 做单测。 */
    static FileContentDTO assemble(String fileNo, RawFileDTO raw) {
        if (raw == null || raw.getContent() == null || raw.getContent().length == 0) {
            // 上游故障或文件不存在必须显式失败，不降级返回默认文件
            throw new IllegalStateException("文件不存在或内容为空: " + fileNo);
        }
        String fileName = MimeTypeResolver.safeFileName(raw.getFileName(), raw.getFileType(), fileNo);
        String mimeType = MimeTypeResolver.resolve(raw.getFileType(), fileName);
        String fileType = MimeTypeResolver.normalizeExtension(raw.getFileType(), fileName);
        return new FileContentDTO(fileNo, fileName, mimeType, fileType, raw.getContent());
    }
}
