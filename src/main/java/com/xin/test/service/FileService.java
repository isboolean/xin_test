package com.xin.test.service;

import com.xin.test.dto.FileContentDTO;

/**
 * 文件服务接口
 */
public interface FileService {

    /**
     * 按文件编号获取文件内容（元数据由服务层从上游 fileType/fileName 归一化）
     *
     * @param fileNo 文件编号
     * @return 文件二进制内容
     */
    byte[] downloadFile(String fileNo);

    /**
     * 按文件编号获取文件内容及元数据（二进制 + 名称 + MIME + 大小）
     *
     * @param fileNo 文件编号
     * @return 文件内容与元数据
     */
    FileContentDTO downloadFileDetail(String fileNo);
}
