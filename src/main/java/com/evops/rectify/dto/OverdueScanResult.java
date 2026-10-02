package com.evops.rectify.dto;

import com.evops.rectify.entity.RectifyEscalation;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 逾期扫描结果：本次扫描新抄送了哪些单子、各升到哪一级。 */
@Data
public class OverdueScanResult {
    /** 扫描时刻在办（未销项）单数 */
    private int openCount;
    /** 本次新产生的抄送记录 */
    private List<RectifyEscalation> escalated = new ArrayList<>();
}
