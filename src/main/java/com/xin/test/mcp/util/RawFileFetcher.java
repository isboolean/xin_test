package com.xin.test.mcp.util;

import com.xin.test.mcp.dto.RawFileDTO;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;

/**
 * 上游原始文件取数工具：线上替换为文件系统接口调用，把返回的
 * byte[] + fileType + fileName 包装为 {@link RawFileDTO}。
 * <p>
 * 本地为可运行占位实现，读取随应用打包的示例 PDF。
 */
public final class RawFileFetcher {

    private static final String BUNDLED_RESOURCE = "file/wufang-survey-report-fixed.pdf";
    private static final String BUNDLED_FILE_TYPE = ".pdf";
    private static final String BUNDLED_FILE_NAME = "五方踏勘报告_模拟数据_修正版.pdf";

    private RawFileFetcher() {
    }

    /**
     * 线上接入点：替换为调用文件系统接口，返回上游报文（内容 + 类型 + 文件名）。
     *
     * @param fileNo 文件编号
     * @return 上游原始文件报文
     */
    public static RawFileDTO fetchRawFile(String fileNo) {
        try (InputStream in = new ClassPathResource(BUNDLED_RESOURCE).getInputStream()) {
            return new RawFileDTO(StreamUtils.copyToByteArray(in), BUNDLED_FILE_TYPE, BUNDLED_FILE_NAME);
        } catch (IOException e) {
            throw new IllegalStateException("读取文件失败: " + fileNo, e);
        }
    }
}
