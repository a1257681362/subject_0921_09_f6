package com.evops.rectify.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 逾期升级抄送记录（只追加）：每跨过一个处理期限自动往上抄送一级。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_rectify_escalation")
public class RectifyEscalation extends BaseEntity {
    private Long rectifyId;
    private String rectifyNo;
    /** 抄送层级 1 维护商主管 / 2 运营负责人 */
    private Integer levelNo;
    private String targetRole;
    private String targetName;
    private String reason;
    private LocalDateTime deadlineAt;
    /** 触发阶段：OVERDUE 逾首限 / OVERDUE_GRACE 逾宽限 */
    private String overdueStage;
    private LocalDateTime escalateTime;
}
