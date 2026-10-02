package com.evops.rectify.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 现场异常上报：点位、类型必填；照片在 multipart 里可传可不传；描述选填。 */
@Data
public class ReportRequest {
    @NotBlank(message = "点位编号不能为空（巡查员须在点位现场建档上报）")
    private String siteNo;
    /** EDGE_LIFT 牌面翘边 / PANEL_DAMAGE 画面破损 / LIGHT_FAULT 亮灯故障 / STRUCTURE_RISK 结构隐患 / OTHER */
    @NotBlank(message = "异常类型不能为空")
    private String issueType;
    /** 现场情况描述 */
    private String issueDesc;
    /** URGENT 紧急 / GENERAL 一般，留空按一般 */
    private String severity;
    /** 处理期限（小时），留空取系统默认 SLA */
    private Integer deadlineHours;
    /** 上报巡查员，留空取当前登录账号 */
    private String operatorName;
    private String remark;
}
