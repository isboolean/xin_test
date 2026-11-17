package com.xin.test.mapper;

import com.xin.test.entity.FileRecordEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 文件记录 Mapper
 */
@Mapper
public interface FileRecordMapper {

    /**
     * 按文件编号查询文件记录
     */
    FileRecordEntity selectByFileNo(@Param("fileNo") String fileNo);
}
