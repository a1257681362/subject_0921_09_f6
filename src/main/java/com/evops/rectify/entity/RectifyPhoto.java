package com.evops.rectify.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 现场留证照片：上报照片与整改回传照片分 phase 记。
 * 单子关闭前一律 KEPT——系统不提供删除接口；关闭默认也不删（留证完整）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_rectify_photo")
public class RectifyPhoto extends BaseEntity {
    private Long rectifyId;
    private String rectifyNo;
    /** 阶段 REPORT 上报留证 / RETURN 整改回传 */
    private String phase;
    private String fileName;
    private String contentType;
    private Long fileSize;
    /** 相对存储路径 */
    private String storagePath;
    private String sha256;
    /** 留存状态 KEPT 在单留证 / ARCHIVED 已归档 */
    private String retainStatus;
    private String uploaderName;
    private LocalDateTime uploadTime;
}
