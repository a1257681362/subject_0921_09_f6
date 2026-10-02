package com.evops.rectify.dto;

import com.evops.rectify.entity.RectifyEscalation;
import com.evops.rectify.entity.RectifyEvent;
import com.evops.rectify.entity.RectifyOrder;
import com.evops.rectify.entity.RectifyPhoto;
import lombok.Data;

import java.util.List;

/** 异常整改单全档：主档 + 留证照片 + 逐动作事件留底 + 逾期抄送记录。 */
@Data
public class RectifyDetailView {
    private RectifyOrder order;
    private List<RectifyPhoto> photos;
    private List<RectifyEvent> events;
    private List<RectifyEscalation> escalations;
}
