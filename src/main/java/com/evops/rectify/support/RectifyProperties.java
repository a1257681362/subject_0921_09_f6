package com.evops.rectify.support;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 异常整改线配置：照片落盘根目录与处理期限（SLA）。 */
@Data
@Component
@ConfigurationProperties(prefix = "rectify")
public class RectifyProperties {

    /** 现场留证照片落盘根目录，库里只存相对路径 */
    private String storageRoot = "./data/rectify-photos";

    private Sla sla = new Sla();

    @Data
    public static class Sla {
        /** 上报后默认处理期限（小时）：上报时刻 + 该时长 = deadline */
        private int deadlineHours = 72;
        /** 跨级宽限（小时）：超过 deadline 再拖这么久仍未销项，抄送再上一级 */
        private int graceHours = 48;
        /** 逾期扫描间隔（毫秒） */
        private long scanIntervalMs = 300_000L;
        /** 启动后首次扫描延迟（毫秒） */
        private long scanInitialDelayMs = 30_000L;
    }
}
