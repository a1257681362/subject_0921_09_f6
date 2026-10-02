package com.evops.rectify.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 维护商处理完回传：处理说明必填；整改后照片在 multipart 里可传可不传。 */
@Data
public class ReturnRequest {
    @NotBlank(message = "处理回传必须填写处理说明（怎么修的、用了什么料）")
    private String finishNote;
    /** 实际处理人，留空取当前登录账号 */
    private String operatorName;
    private String remark;
}
