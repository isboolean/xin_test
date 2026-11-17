package com.xin.test.service.impl;

import com.xin.test.dto.FileContentDTO;
import com.xin.test.entity.FileRecordEntity;
import com.xin.test.mapper.FileRecordMapper;
import com.xin.test.service.FileService;
import com.xin.test.util.MockPdfBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 文件服务实现
 * <p>
 * 说明：当前使用 mock 数据生成文件内容（结构合法的单页 PDF），不调用外部服务、不做真实下载。
 * Mapper 仅用于演示 MyBatis 数据链路（示例表 t_file_record），查询失败时降级为纯 mock。
 */
@Service
public class FileServiceImpl implements FileService {

    private static final Logger log = LoggerFactory.getLogger(FileServiceImpl.class);

    private static final String MIME_PDF = "application/pdf";

    private final FileRecordMapper fileRecordMapper;

    public FileServiceImpl(FileRecordMapper fileRecordMapper) {
        this.fileRecordMapper = fileRecordMapper;
    }

    @Override
    public byte[] downloadFile(String fileNo) {
        return downloadFileDetail(fileNo).getContent();
    }

    @Override
    public FileContentDTO downloadFileDetail(String fileNo) {
        FileRecordEntity record = null;
        try {
            record = fileRecordMapper.selectByFileNo(fileNo);
        } catch (Exception e) {
            // 数据库不可用时不阻断下载能力，降级为纯 mock 元数据
            log.warn("查询文件记录失败，使用 mock 元数据。fileNo={}, reason={}", fileNo, e.getMessage());
        }

        String fileName = record != null && record.getFileName() != null
                ? record.getFileName()
                : "mock_" + fileNo + ".pdf";
        String mimeType = record != null && record.getContentType() != null
                ? record.getContentType()
                : MIME_PDF;

        byte[] content = MockPdfBuilder.build(fileNo, fileName);
        log.info("生成 mock 文件内容，fileNo={}, fileName={}, size={}B", fileNo, fileName, content.length);
        return new FileContentDTO(fileNo, fileName, mimeType, content);
    }
}
