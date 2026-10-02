package com.evops.rectify.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 异常整改单主档：巡查员现场上报 → 系统派单给维护商 → 处理回传 → 复勘通过销项。
 * status 为异常上报状态；versionNo 为异常上报版本（每个动作推进一版）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_rectify_order")
public class RectifyOrder extends BaseEntity {
    /** 整改单号 RC+序列，全局唯一 */
    private String rectifyNo;
    private Long siteId;
    /** 点位编号快照（翻点位历史异常时与点位编号对齐） */
    private String siteNo;
    /** 点位商圈快照 */
    private String siteDistrict;
    /** 异常类型 EDGE_LIFT/PANEL_DAMAGE/LIGHT_FAULT/STRUCTURE_RISK/OTHER */
    private String issueType;
    private String issueDesc;
    /** 严重程度 URGENT 紧急 / GENERAL 一般 */
    private String severity;
    /** 上报巡查员 */
    private String reporterName;
    private LocalDateTime reportTime;
    /** 处理期限（SLA）：超过它未销项即逾期并自动抄送 */
    private LocalDateTime deadline;

    /** 承接维护商 */
    private String vendor;
    private LocalDateTime dispatchTime;
    private String dispatcherName;

    /** 维护商处理回传 */
    private String finishNote;
    private LocalDateTime finishTime;
    private String vendorHandler;

    /** 复勘 */
    private LocalDateTime reviewTime;
    private String reviewerName;
    private String reviewComment;
    private LocalDateTime closeTime;

    /** 异常上报状态 REPORTED/DISPATCHED/RETURNED/REVIEW_REJECTED/CLOSED */
    private String status;
    /** 逾期标记 ON_TIME/OVERDUE，曾逾期即置 OVERDUE 不回落 */
    private String overdueFlag;
    /** 升级抄送层级 0/1/2 */
    private Integer escalateLevel;
    /** 留证照片数（上报+回传，KEPT 口径） */
    private Integer photoCount;
    /** 异常上报版本号 */
    private Integer versionNo;

    private String operatorName;
    private String remark;
}
