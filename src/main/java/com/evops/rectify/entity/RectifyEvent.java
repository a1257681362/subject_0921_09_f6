package com.evops.rectify.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 异常单逐动作事件留底（只追加）：每个动作一条，带动作前后状态、版本号与主档快照。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_rectify_event")
public class RectifyEvent extends BaseEntity {
    private Long rectifyId;
    private String rectifyNo;
    /** 事件类型 REPORT/DISPATCH/RETURN/REVIEW_PASS/REVIEW_REJECT/OVERDUE_ESCALATE/PHOTO_ARCHIVE */
    private String eventType;
    private String fromStatus;
    private String toStatus;
    private Integer versionNo;
    private String operatorName;
    private LocalDateTime eventTime;
    private String detail;
    /** 动作后主档 JSON 快照 */
    private String snapshot;
}
