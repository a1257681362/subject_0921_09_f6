package com.evops.rectify;

import com.evops.common.BusinessException;
import com.evops.ledger.dto.SiteCreateRequest;
import com.evops.ledger.entity.Site;
import com.evops.ledger.service.SiteService;
import com.evops.rectify.dto.DispatchRequest;
import com.evops.rectify.dto.OverdueScanResult;
import com.evops.rectify.dto.ReportRequest;
import com.evops.rectify.dto.RectifyDetailView;
import com.evops.rectify.dto.ReturnRequest;
import com.evops.rectify.dto.ReviewRequest;
import com.evops.rectify.entity.RectifyEscalation;
import com.evops.rectify.entity.RectifyEvent;
import com.evops.rectify.entity.RectifyOrder;
import com.evops.rectify.entity.RectifyPhoto;
import com.evops.rectify.service.RectifyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** F6 异常上报与整改销项端到端（真实 H2 + 真实事务 + 真实落盘，无 Shiro，显式经手人）。 */
@SpringBootTest
class RectifyFlowTest {

    @Autowired private RectifyService rectifyService;
    @Autowired private SiteService siteService;

    private static int seq = 0;
    private synchronized String uniqueNo(String prefix) {
        seq++;
        return prefix + "-" + System.nanoTime() + "-" + seq;
    }

    private Site registerSite() {
        SiteCreateRequest sr = new SiteCreateRequest();
        sr.setSiteNo(uniqueNo("RECT-SITE"));
        sr.setSizeSpec("18m×6m");
        sr.setOrientation("朝北");
        sr.setBusinessDistrict("整改测试商圈");
        sr.setOperatorName("tester");
        return siteService.register(sr);
    }

    private ReportRequest reportReq(Site site, String type) {
        ReportRequest req = new ReportRequest();
        req.setSiteNo(site.getSiteNo());
        req.setIssueType(type);
        req.setIssueDesc("三块牌面翘边，风一大哗啦响");
        req.setOperatorName("inspector1");
        return req;
    }

    private MultipartFile pngPhoto(String name) {
        // 最小 PNG 字节头；F6 只留证不解码，内容不作图像校验
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
        return new MockMultipartFile("photos", name, "image/png", png);
    }

    private DispatchRequest dispatchReq() {
        DispatchRequest req = new DispatchRequest();
        req.setVendor("京诚广告维护");
        req.setOperatorName("dispatcher1");
        return req;
    }

    private ReturnRequest returnReq() {
        ReturnRequest req = new ReturnRequest();
        req.setFinishNote("已重新压条加固，三块翘边全部复位");
        req.setOperatorName("vendor1");
        return req;
    }

    private ReviewRequest reviewReq(String comment) {
        ReviewRequest req = new ReviewRequest();
        req.setComment(comment);
        req.setOperatorName("inspector1");
        return req;
    }

    // ---- 1. 现场上报：照片可传可不传 ----

    @Test
    void report_withoutPhoto_allowed() {
        Site site = registerSite();
        RectifyOrder order = rectifyService.report(reportReq(site, RectifyEnums.TYPE_EDGE_LIFT), null);
        assertTrue(order.getRectifyNo().startsWith("RC"));
        assertEquals(RectifyEnums.ST_REPORTED, order.getStatus());
        assertEquals(0, order.getPhotoCount());
        assertNotNull(order.getDeadline());
        assertTrue(order.getDeadline().isAfter(order.getReportTime()));

        RectifyDetailView d = rectifyService.detail(order.getRectifyNo());
        assertEquals(1, d.getEvents().size());
        assertEquals(RectifyEnums.EV_REPORT, d.getEvents().get(0).getEventType());
        assertTrue(d.getEvents().get(0).getSnapshot().contains("\"rectifyNo\""));
    }

    @Test
    void report_withPhotos_keptAsEvidence() {
        Site site = registerSite();
        List<MultipartFile> photos = Arrays.asList(pngPhoto("a.png"), pngPhoto("b.png"));
        RectifyOrder order = rectifyService.report(reportReq(site, RectifyEnums.TYPE_LIGHT_FAULT), photos);
        assertEquals(2, order.getPhotoCount());

        RectifyDetailView d = rectifyService.detail(order.getRectifyNo());
        assertEquals(2, d.getPhotos().size());
        assertTrue(d.getPhotos().stream().allMatch(p -> RectifyEnums.RETAIN_KEPT.equals(p.getRetainStatus())));
        assertTrue(d.getPhotos().stream().allMatch(p -> p.getSha256() != null && p.getSha256().length() == 64));
        // 文件真落盘、可读
        byte[] bytes = rectifyService.readPhoto(d.getPhotos().get(0));
        assertTrue(bytes.length > 0);
    }

    @Test
    void report_badTypeOrUnknownSite_rejected() {
        Site site = registerSite();
        ReportRequest badType = reportReq(site, "WIND_BLOWN");
        assertThrows(BusinessException.class, () -> rectifyService.report(badType, null));

        ReportRequest noSite = reportReq(site, RectifyEnums.TYPE_OTHER);
        noSite.setSiteNo(uniqueNo("NOT-EXIST"));
        assertThrows(BusinessException.class, () -> rectifyService.report(noSite, null));
    }

    // ---- 2. 完整销项链路 + 版本事件留痕 ----

    @Test
    void fullFlow_reportDispatchReturnReviewPass_closed() {
        Site site = registerSite();
        RectifyOrder o = rectifyService.report(reportReq(site, RectifyEnums.TYPE_EDGE_LIFT),
                Arrays.asList(pngPhoto("scene.png")));
        o = rectifyService.dispatch(o.getRectifyNo(), dispatchReq());
        assertEquals(RectifyEnums.ST_DISPATCHED, o.getStatus());
        assertEquals("京诚广告维护", o.getVendor());

        o = rectifyService.vendorReturn(o.getRectifyNo(), returnReq(),
                Arrays.asList(pngPhoto("fixed.png")));
        assertEquals(RectifyEnums.ST_RETURNED, o.getStatus());

        o = rectifyService.reviewPass(o.getRectifyNo(), reviewReq("现场复看已复位"));
        assertEquals(RectifyEnums.ST_CLOSED, o.getStatus());
        assertNotNull(o.getCloseTime());
        assertEquals(2, o.getPhotoCount());

        RectifyDetailView d = rectifyService.detail(o.getRectifyNo());
        List<String> types = d.getEvents().stream().map(RectifyEvent::getEventType).collect(Collectors.toList());
        assertEquals(Arrays.asList(
                RectifyEnums.EV_REPORT, RectifyEnums.EV_DISPATCH,
                RectifyEnums.EV_RETURN, RectifyEnums.EV_REVIEW_PASS), types);
        // 异常上报版本：上报 v1，每个处置动作推进一版
        assertEquals(Arrays.asList(1, 2, 3, 4),
                d.getEvents().stream().map(RectifyEvent::getVersionNo).collect(Collectors.toList()));
        assertTrue(d.getEvents().stream().allMatch(e -> e.getSnapshot() != null));

        // 关闭即冻结：任何后续处置都拒
        final RectifyOrder closed = o;
        assertThrows(BusinessException.class, () -> rectifyService.dispatch(closed.getRectifyNo(), dispatchReq()));
        assertThrows(BusinessException.class,
                () -> rectifyService.vendorReturn(closed.getRectifyNo(), returnReq(), null));
    }

    @Test
    void dispatch_onlyFromReported_andReviewOnlyFromReturned() {
        Site site = registerSite();
        RectifyOrder o = rectifyService.report(reportReq(site, RectifyEnums.TYPE_OTHER), null);
        // 没派单不能回传
        assertThrows(BusinessException.class,
                () -> rectifyService.vendorReturn(o.getRectifyNo(), returnReq(), null));
        rectifyService.dispatch(o.getRectifyNo(), dispatchReq());
        // 派了单没回传不能复勘
        assertThrows(BusinessException.class,
                () -> rectifyService.reviewPass(o.getRectifyNo(), reviewReq("x")));
    }

    // ---- 3. 复勘驳回 → 重处 → 再复勘通过 ----

    @Test
    void reviewReject_thenRework_thenPass() {
        Site site = registerSite();
        RectifyOrder o = rectifyService.report(reportReq(site, RectifyEnums.TYPE_PANEL_DAMAGE), null);
        rectifyService.dispatch(o.getRectifyNo(), dispatchReq());
        RectifyOrder returned = rectifyService.vendorReturn(o.getRectifyNo(), returnReq(), null);

        // 驳回必须写原因
        assertThrows(BusinessException.class,
                () -> rectifyService.reviewReject(returned.getRectifyNo(), reviewReq("  ")));

        o = rectifyService.reviewReject(returned.getRectifyNo(), reviewReq("东边一块仍翘起两指宽，返工"));
        assertEquals(RectifyEnums.ST_REVIEW_REJECTED, o.getStatus());

        // 驳回态可重新回传
        o = rectifyService.vendorReturn(o.getRectifyNo(), returnReq(), Arrays.asList(pngPhoto("rew.png")));
        assertEquals(RectifyEnums.ST_RETURNED, o.getStatus());

        o = rectifyService.reviewPass(o.getRectifyNo(), reviewReq("复看合格"));
        assertEquals(RectifyEnums.ST_CLOSED, o.getStatus());

        List<String> types = rectifyService.detail(o.getRectifyNo()).getEvents().stream()
                .map(RectifyEvent::getEventType).collect(Collectors.toList());
        assertEquals(Arrays.asList(RectifyEnums.EV_REPORT, RectifyEnums.EV_DISPATCH, RectifyEnums.EV_RETURN,
                RectifyEnums.EV_REVIEW_REJECT, RectifyEnums.EV_RETURN, RectifyEnums.EV_REVIEW_PASS), types);
    }

    // ---- 4. 拖过期限自动逐级往上抄送 ----

    @Test
    void overdue_autoEscalate_levelByLevel() {
        Site site = registerSite();
        ReportRequest req = reportReq(site, RectifyEnums.TYPE_STRUCTURE_RISK);
        req.setDeadlineHours(1);
        RectifyOrder o = rectifyService.report(req, null);
        LocalDateTime deadline = o.getDeadline();
        // 全库扫描可能带上其他用例的在办单，统一只看本单
        java.util.function.Predicate<RectifyEscalation> mine =
                e -> o.getRectifyNo().equals(e.getRectifyNo());

        // 期限内扫描：无抄送
        OverdueScanResult before = rectifyService.scanOverdue(deadline.minusHours(1));
        assertEquals(0, before.getEscalated().stream().filter(mine).count());
        assertEquals(RectifyEnums.OD_ON_TIME, rectifyService.requireByNo(o.getRectifyNo()).getOverdueFlag());

        // 过期限：抄送维护商主管（level 1），标逾期，状态仍是待派单（抄送不动业务状态）
        OverdueScanResult first = rectifyService.scanOverdue(deadline.plusMinutes(1));
        List<RectifyEscalation> firstMine = first.getEscalated().stream().filter(mine).collect(Collectors.toList());
        assertEquals(1, firstMine.size());
        RectifyEscalation e1 = firstMine.get(0);
        assertEquals(1, e1.getLevelNo());
        assertEquals(RectifyEnums.ROLE_VENDOR_SUPERVISOR, e1.getTargetRole());
        RectifyOrder after1 = rectifyService.requireByNo(o.getRectifyNo());
        assertEquals(RectifyEnums.OD_OVERDUE, after1.getOverdueFlag());
        assertEquals(1, after1.getEscalateLevel());
        assertEquals(RectifyEnums.ST_REPORTED, after1.getStatus());

        // 同一层级重复扫描不重复抄送
        assertEquals(0, rectifyService.scanOverdue(deadline.plusMinutes(2)).getEscalated().stream().filter(mine).count());

        // 再过宽限（测试配置 48h）：抄送运营负责人（level 2）
        OverdueScanResult second = rectifyService.scanOverdue(deadline.plusHours(49));
        List<RectifyEscalation> secondMine = second.getEscalated().stream().filter(mine).collect(Collectors.toList());
        assertEquals(1, secondMine.size());
        assertEquals(2, secondMine.get(0).getLevelNo());
        assertEquals(RectifyEnums.ROLE_OPERATIONS_MANAGER, secondMine.get(0).getTargetRole());
        assertEquals(2, rectifyService.requireByNo(o.getRectifyNo()).getEscalateLevel());

        // 抄送记录与事件都在单子全档里
        RectifyDetailView d = rectifyService.detail(o.getRectifyNo());
        assertEquals(2, d.getEscalations().size());
        assertTrue(d.getEvents().stream()
                .anyMatch(e -> RectifyEnums.EV_OVERDUE_ESCALATE.equals(e.getEventType())
                        && e.getVersionNo().equals(1)));
    }

    @Test
    void overdue_closedOrder_notEscalated() {
        Site site = registerSite();
        ReportRequest req = reportReq(site, RectifyEnums.TYPE_EDGE_LIFT);
        req.setDeadlineHours(1);
        RectifyOrder o = rectifyService.report(req, null);
        rectifyService.dispatch(o.getRectifyNo(), dispatchReq());
        RectifyOrder returned = rectifyService.vendorReturn(o.getRectifyNo(), returnReq(), null);
        RectifyOrder closed = rectifyService.reviewPass(returned.getRectifyNo(), reviewReq("合格"));
        // 哪怕扫描时刻远超期限，已销项单不再抄送
        OverdueScanResult r = rectifyService.scanOverdue(closed.getDeadline().plusHours(500));
        assertEquals(0, r.getEscalated().stream()
                .filter(e -> closed.getRectifyNo().equals(e.getRectifyNo())).count());
    }

    // ---- 5. 照片在单子关闭前不许删 ----

    @Test
    void photos_cannotDeleteBeforeClose_archiveAfterClose() {
        Site site = registerSite();
        RectifyOrder reported = rectifyService.report(reportReq(site, RectifyEnums.TYPE_EDGE_LIFT),
                Arrays.asList(pngPhoto("keep.png")));
        // 未销项：归档（物理删除）一律拒
        assertThrows(BusinessException.class,
                () -> rectifyService.archivePhotosAfterClose(reported.getRectifyNo(), "admin"));

        rectifyService.dispatch(reported.getRectifyNo(), dispatchReq());
        RectifyOrder returned = rectifyService.vendorReturn(reported.getRectifyNo(), returnReq(), null);
        // 待复勘也不许删
        assertThrows(BusinessException.class,
                () -> rectifyService.archivePhotosAfterClose(returned.getRectifyNo(), "admin"));

        RectifyOrder o = rectifyService.reviewPass(returned.getRectifyNo(), reviewReq("合格"));
        int archived = rectifyService.archivePhotosAfterClose(o.getRectifyNo(), "admin");
        assertEquals(1, archived);
        assertEquals(0, rectifyService.requireByNo(o.getRectifyNo()).getPhotoCount());
        // 记录不抹：照片行置 ARCHIVED，留痕完整
        RectifyPhoto photo = rectifyService.detail(o.getRectifyNo()).getPhotos().get(0);
        assertEquals(RectifyEnums.RETAIN_ARCHIVED, photo.getRetainStatus());
        // 再跑一次是 0：不重复删
        assertEquals(0, rectifyService.archivePhotosAfterClose(o.getRectifyNo(), "admin"));
    }

    // ---- 6. 点位档案可翻历史异常 ----

    @Test
    void siteHistory_listsAllIncidents_newestFirst() {
        Site site = registerSite();
        RectifyOrder o1 = rectifyService.report(reportReq(site, RectifyEnums.TYPE_EDGE_LIFT), null);
        RectifyOrder o2 = rectifyService.report(reportReq(site, RectifyEnums.TYPE_LIGHT_FAULT), null);

        // 第一张走完销项
        rectifyService.dispatch(o1.getRectifyNo(), dispatchReq());
        o1 = rectifyService.vendorReturn(o1.getRectifyNo(), returnReq(), null);
        rectifyService.reviewPass(o1.getRectifyNo(), reviewReq("合格"));

        List<RectifyOrder> history = rectifyService.listSiteHistory(site.getSiteNo());
        List<String> nos = history.stream().map(RectifyOrder::getRectifyNo).collect(Collectors.toList());
        assertEquals(Arrays.asList(o2.getRectifyNo(), o1.getRectifyNo()), nos);
        // 已销项的、在办的都翻得到
        assertEquals(2, history.size());
        assertTrue(history.stream().anyMatch(h -> RectifyEnums.ST_CLOSED.equals(h.getStatus())));
        assertTrue(history.stream().anyMatch(h -> RectifyEnums.ST_REPORTED.equals(h.getStatus())));
    }
}
