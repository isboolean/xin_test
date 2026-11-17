-- 示例数据库初始化脚本
-- 执行方式：mysql -uroot -p < init.sql

CREATE DATABASE IF NOT EXISTS `xin_test` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

USE `xin_test`;

CREATE TABLE IF NOT EXISTS `t_file_record` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `file_no`      VARCHAR(64)  NOT NULL COMMENT '文件编号',
    `file_name`    VARCHAR(255) NOT NULL COMMENT '文件名称',
    `file_size`    BIGINT       NULL COMMENT '文件大小（字节）',
    `content_type` VARCHAR(128) NULL COMMENT '文件类型',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_file_no` (`file_no`)
) ENGINE = InnoDB COMMENT = '文件记录表';

INSERT INTO `t_file_record` (`file_no`, `file_name`, `file_size`, `content_type`)
VALUES ('F20261116001', '测试文件001.txt', 1024, 'text/plain')
ON DUPLICATE KEY UPDATE `file_name` = VALUES(`file_name`);
