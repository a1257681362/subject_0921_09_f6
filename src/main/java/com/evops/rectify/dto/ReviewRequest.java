package com.evops.rectify.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 复勘结论：驳回必须写明原因（维护商要照着返工）；通过可留复勘备注。 */
@Data
public class ReviewRequest {
    /** 复勘意见；驳回时必填 */
    @NotBlank(message = "复勘驳回必须写明不通过原因")
    private String comment;
    /** 复勘人，留空取当前登录账号 */
    private String operatorName;
}
