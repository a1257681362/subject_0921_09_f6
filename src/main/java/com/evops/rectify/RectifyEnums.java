package com.evops.rectify;

/**
 * F6 设施异常上报与整改销项状态口径。
 *
 * - 异常上报状态（挂在整改单上）：
 *   REPORTED 已上报待派单 / DISPATCHED 已派单待处理 / RETURNED 已回传待复勘 /
 *   REVIEW_REJECTED 复勘驳回待重处 / CLOSED 复勘通过已销项；
 * - 异常上报版本：上报即 v1，每次派单/回传/复勘推进一个版本，逐版本事件留底；
 * - 逾期标记：ON_TIME 未逾期 / OVERDUE 已逾期（只上不下，曾逾期就留痕）；
 * - 照片留存：KEPT 在单留证 / ARCHIVED 已随关闭归档（关闭前一律 KEPT，不许删）。
 */
public final class RectifyEnums {
    private RectifyEnums() {}

    /** 异常上报状态 */
    public static final String ST_REPORTED = "REPORTED";
    public static final String ST_DISPATCHED = "DISPATCHED";
    public static final String ST_RETURNED = "RETURNED";
    public static final String ST_REVIEW_REJECTED = "REVIEW_REJECTED";
    public static final String ST_CLOSED = "CLOSED";

    /** 事件类型（异常上报版本推进点） */
    public static final String EV_REPORT = "REPORT";
    public static final String EV_DISPATCH = "DISPATCH";
    public static final String EV_RETURN = "RETURN";
    public static final String EV_REVIEW_PASS = "REVIEW_PASS";
    public static final String EV_REVIEW_REJECT = "REVIEW_REJECT";
    public static final String EV_OVERDUE_ESCALATE = "OVERDUE_ESCALATE";
    public static final String EV_PHOTO_ARCHIVE = "PHOTO_ARCHIVE";

    /** 严重程度 */
    public static final String SEV_URGENT = "URGENT";
    public static final String SEV_GENERAL = "GENERAL";

    /** 逾期标记 */
    public static final String OD_ON_TIME = "ON_TIME";
    public static final String OD_OVERDUE = "OVERDUE";

    /** 照片阶段 */
    public static final String PHASE_REPORT = "REPORT";
    public static final String PHASE_RETURN = "RETURN";

    /** 照片留存状态 */
    public static final String RETAIN_KEPT = "KEPT";
    public static final String RETAIN_ARCHIVED = "ARCHIVED";

    /** 抄送层级角色：1 维护商主管，2 运营负责人 */
    public static final String ROLE_VENDOR_SUPERVISOR = "VENDOR_SUPERVISOR";
    public static final String ROLE_OPERATIONS_MANAGER = "OPERATIONS_MANAGER";

    /** 异常类型：牌面翘边 / 画面破损 / 亮灯故障 / 结构隐患 / 其他 */
    public static final String TYPE_EDGE_LIFT = "EDGE_LIFT";
    public static final String TYPE_PANEL_DAMAGE = "PANEL_DAMAGE";
    public static final String TYPE_LIGHT_FAULT = "LIGHT_FAULT";
    public static final String TYPE_STRUCTURE_RISK = "STRUCTURE_RISK";
    public static final String TYPE_OTHER = "OTHER";

    public static final int FIRST_VERSION_NO = 1;

    public static String statusLabel(String s) {
        switch (s) {
            case ST_REPORTED: return "已上报待派单";
            case ST_DISPATCHED: return "已派单待处理";
            case ST_RETURNED: return "已回传待复勘";
            case ST_REVIEW_REJECTED: return "复勘驳回待重处";
            case ST_CLOSED: return "复勘通过已销项";
            default: return s;
        }
    }

    public static String eventLabel(String s) {
        switch (s) {
            case EV_REPORT: return "现场上报";
            case EV_DISPATCH: return "整改派单";
            case EV_RETURN: return "处理回传";
            case EV_REVIEW_PASS: return "复勘通过销项";
            case EV_REVIEW_REJECT: return "复勘驳回";
            case EV_OVERDUE_ESCALATE: return "逾期抄送升级";
            case EV_PHOTO_ARCHIVE: return "关闭后照片归档";
            default: return s;
        }
    }

    public static String issueTypeLabel(String s) {
        switch (s) {
            case TYPE_EDGE_LIFT: return "牌面翘边";
            case TYPE_PANEL_DAMAGE: return "画面破损";
            case TYPE_LIGHT_FAULT: return "亮灯故障";
            case TYPE_STRUCTURE_RISK: return "结构隐患";
            case TYPE_OTHER: return "其他";
            default: return s;
        }
    }

    public static String severityLabel(String s) {
        return SEV_URGENT.equals(s) ? "紧急" : "一般";
    }

    public static String photoPhaseLabel(String s) {
        return PHASE_REPORT.equals(s) ? "上报留证" : PHASE_RETURN.equals(s) ? "整改回传" : s;
    }

    public static String escalationTargetLabel(String role) {
        return ROLE_VENDOR_SUPERVISOR.equals(role) ? "维护商主管"
                : ROLE_OPERATIONS_MANAGER.equals(role) ? "运营负责人" : role;
    }
}
