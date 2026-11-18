package com.xin.test.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 文件记录实体，对应表 t_file_record
 */
@Getter
@Setter
@ToString(exclude = "createTime")
public class FileRecordEntity {

    private Long id;
    /** 文件编号 */
    private String fileNo;
    /** 文件名称 */
    private String fileName;
    /** 文件大小（字节） */
    private Long fileSize;
    /** 文件类型 */
    private String contentType;
    /** 创建时间 */
    private LocalDateTime createTime;
}
