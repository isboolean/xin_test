package com.xin.test.service.impl;

import com.xin.test.dto.FileContentDTO;
import com.xin.test.dto.RawFileDTO;
import com.xin.test.service.FileService;
import com.xin.test.util.MimeTypeResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;

/**
 * 文件服务实现
 * <p>
 * 只负责把上游返回的 byte[] + fileType + fileName 组装为 FileContentDTO：
 * 文件名做安全清洗、扩展名经 MimeTypeResolver 映射为 MIME。
 * 上游取数逻辑集中在 fetchRawFile，线上替换为文件系统接口调用即可，本类其余逻辑无需改动。
 */
@Service
public class FileServiceImpl implements FileService {

    private static final Logger log = LoggerFactory.getLogger(FileServiceImpl.class);

    private static final String BUNDLED_RESOURCE = "file/decoration-cost-request.docx";
    private static final String BUNDLED_FILE_TYPE = ".docx";
    private static final String BUNDLED_FILE_NAME = "关于装修费用的请示.docx";

    @Override
    public byte[] downloadFile(String fileNo) {
        FileContentDTO detail = downloadFileDetail(fileNo);
        return detail == null ? null : detail.getContent();
    }

    @Override
    public FileContentDTO downloadFileDetail(String fileNo) {
        FileContentDTO dto = assemble(fileNo, fetchRawFile(fileNo));
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

    /**
     * 线上接入点：替换为调用文件系统接口，把返回的 byte[] + fileType + fileName 包装为 RawFileDTO。
     * 本地为可运行占位实现，读取随应用打包的示例 DOCX。
     */
    private RawFileDTO fetchRawFile(String fileNo) {
        try (InputStream in = new ClassPathResource(BUNDLED_RESOURCE).getInputStream()) {
            return new RawFileDTO(StreamUtils.copyToByteArray(in), BUNDLED_FILE_TYPE, BUNDLED_FILE_NAME);
        } catch (IOException e) {
            throw new IllegalStateException("读取文件失败: " + fileNo, e);
        }
    }
}
