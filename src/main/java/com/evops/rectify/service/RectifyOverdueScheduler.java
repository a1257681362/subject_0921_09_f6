package com.evops.rectify.service;

import com.evops.rectify.dto.OverdueScanResult;
import com.evops.rectify.entity.RectifyEscalation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 逾期自动抄送：定时扫描未销项整改单，过期限自动逐级往上抄送（维护商主管 → 运营负责人）。 */
@EnableScheduling
@Component
public class RectifyOverdueScheduler {

    private static final Logger log = LoggerFactory.getLogger(RectifyOverdueScheduler.class);

    private final RectifyService rectifyService;

    public RectifyOverdueScheduler(RectifyService rectifyService) {
        this.rectifyService = rectifyService;
    }

    @Scheduled(
            fixedDelayString = "${rectify.sla.scan-interval-ms:300000}",
            initialDelayString = "${rectify.sla.scan-initial-delay-ms:30000}")
    public void scan() {
        try {
            OverdueScanResult result = rectifyService.scanOverdue();
            for (RectifyEscalation e : result.getEscalated()) {
                if (e != null) {
                    log.warn("整改单 {} 逾期未销项，已自动抄送{}（层级 {}）",
                            e.getRectifyNo(), e.getTargetName(), e.getLevelNo());
                }
            }
        } catch (Exception e) {
            // 单次扫描失败不能掐死后续调度
            log.error("整改逾期扫描失败", e);
        }
    }
}
