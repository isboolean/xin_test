package com.xin.test.service;

/**
 * 文件服务接口（REST 下载入口）
 */
public interface FileService {

    /**
     * 按文件编号获取文件内容。具体实现复用 mcp 侧下载能力。
     *
     * @param fileNo 文件编号
     * @return 文件二进制内容
     */
    byte[] downloadFile(String fileNo);
}
