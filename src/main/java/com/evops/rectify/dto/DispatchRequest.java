package com.evops.rectify.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 系统派单：整改单派给承接维护商。 */
@Data
public class DispatchRequest {
    @NotBlank(message = "承接维护商不能为空，整改单必须派到具体维护商")
    private String vendor;
    /** 派单人，留空取当前登录账号 */
    private String operatorName;
    private String remark;
}
