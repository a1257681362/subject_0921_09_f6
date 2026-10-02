package com.evops.rectify.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BusinessException;
import com.evops.ledger.entity.Site;
import com.evops.ledger.service.SiteService;
import com.evops.ledger.support.Operators;
import com.evops.rectify.RectifyEnums;
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
import com.evops.rectify.mapper.RectifyEscalationMapper;
import com.evops.rectify.mapper.RectifyEventMapper;
import com.evops.rectify.mapper.RectifyOrderMapper;
import com.evops.rectify.mapper.RectifyPhotoMapper;
import com.evops.rectify.support.RectifyProperties;
import com.evops.rectify.support.RectifyStorage;
import com.evops.asset.support.ImageFingerprints;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 异常上报整改线主服务。
 *
 * 状态机：REPORTED 上报 → DISPATCHED 派单 → RETURNED 回传 → CLOSED 复勘通过销项；
 * 复勘驳回 RETURNED → REVIEW_REJECTED，维护商重处再回传 RETURNED。
 * 每个处置动作推进一个异常上报版本并写只追加事件（带前后状态与主档快照）。
 * 逾期不销项由 {@link #scanOverdue(LocalDateTime)} 自动逐级抄送（维护商主管 → 运营负责人）。
 */
@Service
public class RectifyService {

    private static final List<String> ALLOWED_CONTENT = Arrays.asList(
            "image/jpeg", "image/jpg", "image/png", "image/gif");

    private final RectifyOrderMapper orderMapper;
    private final RectifyEventMapper eventMapper;
    private final RectifyPhotoMapper photoMapper;
    private final RectifyEscalationMapper escalationMapper;
    private final SiteService siteService;
    private final RectifyStorage storage;
    private final RectifyProperties properties;
    private final ObjectMapper objectMapper;

    public RectifyService(RectifyOrderMapper orderMapper,
                          RectifyEventMapper eventMapper,
                          RectifyPhotoMapper photoMapper,
                          RectifyEscalationMapper escalationMapper,
                          SiteService siteService,
                          RectifyStorage storage,
                          RectifyProperties properties,
                          ObjectMapper objectMapper) {
        this.orderMapper = orderMapper;
        this.eventMapper = eventMapper;
        this.photoMapper = photoMapper;
        this.escalationMapper = escalationMapper;
        this.siteService = siteService;
        this.storage = storage;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    // ---- 现场上报 ----

    /**
     * 巡查员现场提交异常：点位、类型必填，照片可传可不传。
     * 系统即生成整改单（RC+序列）进待派单，并按 SLA 落死处理期限。
     */
    @Transactional(rollbackFor = Exception.class)
    public RectifyOrder report(ReportRequest request, List<MultipartFile> photos) {
        String issueType = normalizeType(request.getIssueType());
        Site site = siteService.getByNo(request.getSiteNo().trim());

        LocalDateTime now = LocalDateTime.now();
        int hours = request.getDeadlineHours() == null
                ? properties.getSla().getDeadlineHours() : request.getDeadlineHours();
        if (hours <= 0) {
            throw new BusinessException("处理期限必须大于 0 小时");
        }
        String severity = RectifyEnums.SEV_URGENT.equals(
                request.getSeverity() == null ? "" : request.getSeverity().trim().toUpperCase())
                ? RectifyEnums.SEV_URGENT : RectifyEnums.SEV_GENERAL;
        String operator = Operators.resolve(request.getOperatorName());

        RectifyOrder order = new RectifyOrder();
        order.setRectifyNo("RC" + orderMapper.nextRectifySeq());
        order.setSiteId(site.getId());
        order.setSiteNo(site.getSiteNo());
        order.setSiteDistrict(site.getBusinessDistrict());
        order.setIssueType(issueType);
        order.setIssueDesc(trimTo(request.getIssueDesc(), 1000));
        order.setSeverity(severity);
        order.setReporterName(operator);
        order.setReportTime(now);
        order.setDeadline(now.plusHours(hours));
        order.setStatus(RectifyEnums.ST_REPORTED);
        order.setOverdueFlag(RectifyEnums.OD_ON_TIME);
        order.setEscalateLevel(0);
        order.setPhotoCount(0);
        order.setVersionNo(RectifyEnums.FIRST_VERSION_NO);
        order.setOperatorName(operator);
        order.setRemark(request.getRemark());
        orderMapper.insert(order);

        int photoCount = savePhotos(order, RectifyEnums.PHASE_REPORT, photos, operator);
        if (photoCount > 0) {
            order.setPhotoCount(photoCount);
            applyPhotoCount(order);
        }

        appendEvent(order, RectifyEnums.EV_REPORT, null, RectifyEnums.ST_REPORTED, operator,
                "现场上报异常：" + RectifyEnums.issueTypeLabel(issueType)
                        + "；处理期限 " + order.getDeadline()
                        + (photoCount > 0 ? "；上报留证 " + photoCount + " 张" : "；未附照片（允许）"));
        return order;
    }

    // ---- 派单 ----

    /** 系统把整改单派给承接维护商。 */
    @Transactional(rollbackFor = Exception.class)
    public RectifyOrder dispatch(String rectifyNo, DispatchRequest request) {
        RectifyOrder order = requireByNo(rectifyNo);
        requireStatus(order, RectifyEnums.ST_REPORTED, "派单");
        String operator = Operators.resolve(request.getOperatorName());

        LocalDateTime now = LocalDateTime.now();
        order.setVendor(request.getVendor().trim());
        order.setDispatcherName(operator);
        order.setDispatchTime(now);
        order.setStatus(RectifyEnums.ST_DISPATCHED);
        bumpVersion(order);
        orderMapper.updateById(order);
        appendEvent(order, RectifyEnums.EV_DISPATCH, RectifyEnums.ST_REPORTED, RectifyEnums.ST_DISPATCHED, operator,
                "整改单派给维护商：" + order.getVendor());
        return order;
    }

    // ---- 处理回传 ----

    /** 维护商处理完回传；首次处理与复勘驳回后的返工都走这里，整改后照片可传可不传。 */
    @Transactional(rollbackFor = Exception.class)
    public RectifyOrder vendorReturn(String rectifyNo, ReturnRequest request, List<MultipartFile> photos) {
        RectifyOrder order = requireByNo(rectifyNo);
        boolean rework = RectifyEnums.ST_REVIEW_REJECTED.equals(order.getStatus());
        if (!RectifyEnums.ST_DISPATCHED.equals(order.getStatus()) && !rework) {
            throw new BusinessException("只有「已派单待处理」或「复勘驳回待重处」的单子可回传处理结果，当前状态："
                    + RectifyEnums.statusLabel(order.getStatus()));
        }
        String operator = Operators.resolve(request.getOperatorName());
        LocalDateTime now = LocalDateTime.now();

        order.setFinishNote(trimTo(request.getFinishNote(), 1000));
        order.setFinishTime(now);
        order.setVendorHandler(operator);
        order.setStatus(RectifyEnums.ST_RETURNED);
        bumpVersion(order);
        orderMapper.updateById(order);

        int added = savePhotos(order, RectifyEnums.PHASE_RETURN, photos, operator);
        if (added > 0) {
            applyPhotoCount(order);
        }
        appendEvent(order, RectifyEnums.EV_RETURN,
                rework ? RectifyEnums.ST_REVIEW_REJECTED : RectifyEnums.ST_DISPATCHED,
                RectifyEnums.ST_RETURNED, operator,
                (rework ? "复勘驳回后返工处理回传" : "维护商处理完成回传")
                        + "；回传照片 " + added + " 张"
                        + (order.getDeadline().isBefore(now) ? "；回传时已超过处理期限" : ""));
        return order;
    }

    // ---- 复勘 ----

    /** 复勘通过：唯一销项路径，关闭即冻结。 */
    @Transactional(rollbackFor = Exception.class)
    public RectifyOrder reviewPass(String rectifyNo, ReviewRequest request) {
        RectifyOrder order = requireByNo(rectifyNo);
        requireStatus(order, RectifyEnums.ST_RETURNED, "复勘");
        String operator = Operators.resolve(request.getOperatorName());
        LocalDateTime now = LocalDateTime.now();

        order.setReviewerName(operator);
        order.setReviewComment(trimTo(request.getComment(), 500));
        order.setReviewTime(now);
        order.setCloseTime(now);
        order.setStatus(RectifyEnums.ST_CLOSED);
        bumpVersion(order);
        orderMapper.updateById(order);
        appendEvent(order, RectifyEnums.EV_REVIEW_PASS, RectifyEnums.ST_RETURNED, RectifyEnums.ST_CLOSED, operator,
                "复勘通过，准予销项" + (request.getComment() != null && !request.getComment().trim().isEmpty()
                        ? "：" + request.getComment().trim() : "")
                        + "。留证照片 " + order.getPhotoCount() + " 张随单封存，关闭前不允许删除");
        return order;
    }

    /** 复勘驳回：必须写明不通过原因，单子退回维护商重处。 */
    @Transactional(rollbackFor = Exception.class)
    public RectifyOrder reviewReject(String rectifyNo, ReviewRequest request) {
        RectifyOrder order = requireByNo(rectifyNo);
        requireStatus(order, RectifyEnums.ST_RETURNED, "复勘驳回");
        if (request.getComment() == null || request.getComment().trim().isEmpty()) {
            throw new BusinessException("复勘驳回必须写明不通过原因，维护商要照着重处");
        }
        String operator = Operators.resolve(request.getOperatorName());
        LocalDateTime now = LocalDateTime.now();

        order.setReviewerName(operator);
        order.setReviewComment(trimTo(request.getComment(), 500));
        order.setReviewTime(now);
        order.setStatus(RectifyEnums.ST_REVIEW_REJECTED);
        bumpVersion(order);
        orderMapper.updateById(order);
        appendEvent(order, RectifyEnums.EV_REVIEW_REJECT, RectifyEnums.ST_RETURNED,
                RectifyEnums.ST_REVIEW_REJECTED, operator,
                "复勘不通过，退回维护商重处：" + request.getComment().trim());
        return order;
    }

    // ---- 逾期自动抄送 ----

    /**
     * 扫描全部未销项单：过了处理期限未销项 → 抄送维护商主管（level 1）并标逾期；
     * 再过宽限期仍未销项 → 抄送运营负责人（level 2）。每层只抄送一次（唯一约束兜底）。
     */
    @Transactional(rollbackFor = Exception.class)
    public OverdueScanResult scanOverdue(LocalDateTime now) {
        OverdueScanResult result = new OverdueScanResult();
        List<RectifyOrder> openOrders = orderMapper.selectList(new LambdaQueryWrapper<RectifyOrder>()
                .ne(RectifyOrder::getStatus, RectifyEnums.ST_CLOSED));
        result.setOpenCount(openOrders.size());
        for (RectifyOrder order : openOrders) {
            int level = order.getEscalateLevel() == null ? 0 : order.getEscalateLevel();
            if (level == 0 && now.isAfter(order.getDeadline())) {
                result.getEscalated().add(escalate(order, 1, RectifyEnums.ROLE_VENDOR_SUPERVISOR,
                        "OVERDUE", order.getDeadline(), now,
                        "超过处理期限 " + order.getDeadline() + " 仍未销项，自动抄送维护商主管督办"));
            } else if (level == 1
                    && now.isAfter(order.getDeadline().plusHours(properties.getSla().getGraceHours()))) {
                result.getEscalated().add(escalate(order, 2, RectifyEnums.ROLE_OPERATIONS_MANAGER,
                        "OVERDUE_GRACE", order.getDeadline(), now,
                        "超过处理期限及宽限仍未销项，自动再抄送运营负责人"));
            }
        }
        return result;
    }

    public OverdueScanResult scanOverdue() {
        return scanOverdue(LocalDateTime.now());
    }

    private RectifyEscalation escalate(RectifyOrder order, int level, String targetRole,
                                       String stage, LocalDateTime deadline, LocalDateTime now, String reason) {
        RectifyEscalation escalation = new RectifyEscalation();
        escalation.setRectifyId(order.getId());
        escalation.setRectifyNo(order.getRectifyNo());
        escalation.setLevelNo(level);
        escalation.setTargetRole(targetRole);
        escalation.setTargetName(RectifyEnums.escalationTargetLabel(targetRole));
        escalation.setReason(reason);
        escalation.setDeadlineAt(deadline);
        escalation.setOverdueStage(stage);
        escalation.setEscalateTime(now);
        try {
            escalationMapper.insert(escalation);
        } catch (DuplicateKeyException e) {
            // 并发扫描下同层级已抄送，跳过不重复
            return null;
        }

        order.setEscalateLevel(level);
        order.setOverdueFlag(RectifyEnums.OD_OVERDUE);
        orderMapper.updateById(order);
        appendEvent(order, RectifyEnums.EV_OVERDUE_ESCALATE, order.getStatus(), order.getStatus(),
                "SYSTEM", reason + "（抄送层级升至 " + level + "）");
        return escalation;
    }

    // ---- 照片留证 ----

    /** 关闭后归档（默认不执行，留证完整）：只准对已销项单做，关闭前调用一律拒。 */
    @Transactional(rollbackFor = Exception.class)
    public int archivePhotosAfterClose(String rectifyNo, String operatorName) {
        RectifyOrder order = requireByNo(rectifyNo);
        if (!RectifyEnums.ST_CLOSED.equals(order.getStatus())) {
            throw new BusinessException("单子尚未销项，留证照片一张都不许删（当前状态："
                    + RectifyEnums.statusLabel(order.getStatus()) + "）");
        }
        String operator = Operators.resolve(operatorName);
        List<RectifyPhoto> photos = photoMapper.selectList(new LambdaQueryWrapper<RectifyPhoto>()
                .eq(RectifyPhoto::getRectifyId, order.getId())
                .eq(RectifyPhoto::getRetainStatus, RectifyEnums.RETAIN_KEPT));
        int archived = 0;
        for (RectifyPhoto photo : photos) {
            storage.deletePhysically(photo.getStoragePath());
            photo.setRetainStatus(RectifyEnums.RETAIN_ARCHIVED);
            photoMapper.updateById(photo);
            archived++;
        }
        if (archived > 0) {
            applyPhotoCount(order);
            appendEvent(order, RectifyEnums.EV_PHOTO_ARCHIVE, RectifyEnums.ST_CLOSED, RectifyEnums.ST_CLOSED,
                    operator, "销项后按留存策略归档留证照片 " + archived + " 张（关闭前不允许删除）");
        }
        return archived;
    }

    public RectifyPhoto requirePhoto(Long photoId) {
        RectifyPhoto photo = photoMapper.selectById(photoId);
        if (photo == null) {
            throw new BusinessException("留证照片不存在: id=" + photoId);
        }
        return photo;
    }

    public byte[] readPhoto(RectifyPhoto photo) {
        return storage.read(photo.getStoragePath());
    }

    private int savePhotos(RectifyOrder order, String phase, List<MultipartFile> photos, String uploader) {
        if (photos == null) {
            return 0;
        }
        int count = 0;
        for (MultipartFile file : photos) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
            if (!ALLOWED_CONTENT.contains(contentType)) {
                throw new BusinessException("留证照片只支持 JPG/PNG/GIF，收到：" + file.getContentType());
            }
            byte[] bytes = readBytes(file);
            String sha = ImageFingerprints.sha256(bytes);
            String path = storage.store(order.getId(), phase, file.getOriginalFilename(), bytes);

            RectifyPhoto photo = new RectifyPhoto();
            photo.setRectifyId(order.getId());
            photo.setRectifyNo(order.getRectifyNo());
            photo.setPhase(phase);
            photo.setFileName(file.getOriginalFilename());
            photo.setContentType(contentType);
            photo.setFileSize((long) bytes.length);
            photo.setStoragePath(path);
            photo.setSha256(sha);
            photo.setRetainStatus(RectifyEnums.RETAIN_KEPT);
            photo.setUploaderName(uploader);
            photo.setUploadTime(LocalDateTime.now());
            photoMapper.insert(photo);
            count++;
        }
        return count;
    }

    private void applyPhotoCount(RectifyOrder order) {
        Long kept = photoMapper.selectCount(new LambdaQueryWrapper<RectifyPhoto>()
                .eq(RectifyPhoto::getRectifyId, order.getId())
                .eq(RectifyPhoto::getRetainStatus, RectifyEnums.RETAIN_KEPT));
        RectifyOrder update = new RectifyOrder();
        update.setId(order.getId());
        int count = kept == null ? 0 : kept.intValue();
        update.setPhotoCount(count);
        order.setPhotoCount(count);
        orderMapper.updateById(update);
    }

    // ---- 查询 ----

    public RectifyOrder requireByNo(String rectifyNo) {
        RectifyOrder order = orderMapper.selectOne(new LambdaQueryWrapper<RectifyOrder>()
                .eq(RectifyOrder::getRectifyNo, rectifyNo.trim()));
        if (order == null) {
            throw new BusinessException("整改单不存在: " + rectifyNo);
        }
        return order;
    }

    /** 翻查：点位 / 单号 / 状态 / 只看逾期；空条件全量，新单在前。 */
    public List<RectifyOrder> search(String siteNo, String status, String rectifyNo, Boolean overdueOnly) {
        LambdaQueryWrapper<RectifyOrder> wrapper = new LambdaQueryWrapper<>();
        if (siteNo != null && !siteNo.trim().isEmpty()) {
            wrapper.eq(RectifyOrder::getSiteNo, siteNo.trim());
        }
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq(RectifyOrder::getStatus, status.trim().toUpperCase());
        }
        if (rectifyNo != null && !rectifyNo.trim().isEmpty()) {
            wrapper.like(RectifyOrder::getRectifyNo, rectifyNo.trim());
        }
        if (Boolean.TRUE.equals(overdueOnly)) {
            wrapper.eq(RectifyOrder::getOverdueFlag, RectifyEnums.OD_OVERDUE);
        }
        wrapper.orderByDesc(RectifyOrder::getReportTime).orderByDesc(RectifyOrder::getId);
        return orderMapper.selectList(wrapper);
    }

    /** 点位档案视角：该点位全部历史异常（含已销项），新的在前。 */
    public List<RectifyOrder> listSiteHistory(String siteNo) {
        Site site = siteService.getByNo(siteNo.trim());
        return orderMapper.selectList(new LambdaQueryWrapper<RectifyOrder>()
                .eq(RectifyOrder::getSiteId, site.getId())
                .orderByDesc(RectifyOrder::getReportTime)
                .orderByDesc(RectifyOrder::getId));
    }

    public RectifyDetailView detail(String rectifyNo) {
        RectifyOrder order = requireByNo(rectifyNo);
        RectifyDetailView view = new RectifyDetailView();
        view.setOrder(order);
        view.setPhotos(photoMapper.selectList(new LambdaQueryWrapper<RectifyPhoto>()
                .eq(RectifyPhoto::getRectifyId, order.getId())
                .orderByAsc(RectifyPhoto::getId)));
        view.setEvents(eventMapper.selectList(new LambdaQueryWrapper<RectifyEvent>()
                .eq(RectifyEvent::getRectifyId, order.getId())
                .orderByAsc(RectifyEvent::getId)));
        view.setEscalations(escalationMapper.selectList(new LambdaQueryWrapper<RectifyEscalation>()
                .eq(RectifyEscalation::getRectifyId, order.getId())
                .orderByAsc(RectifyEscalation::getLevelNo)));
        return view;
    }

    // ---- 内部辅助 ----

    private void requireStatus(RectifyOrder order, String expected, String action) {
        if (RectifyEnums.ST_CLOSED.equals(order.getStatus())) {
            throw new BusinessException("整改单 " + order.getRectifyNo() + " 已复勘销项，关闭即冻结，不能再" + action);
        }
        if (!expected.equals(order.getStatus())) {
            throw new BusinessException("整改单当前状态为「" + RectifyEnums.statusLabel(order.getStatus())
                    + "」，不能" + action + "（需「" + RectifyEnums.statusLabel(expected) + "」）");
        }
    }

    private void bumpVersion(RectifyOrder order) {
        order.setVersionNo((order.getVersionNo() == null ? RectifyEnums.FIRST_VERSION_NO : order.getVersionNo()) + 1);
    }

    private void appendEvent(RectifyOrder order, String eventType, String fromStatus, String toStatus,
                             String operator, String detail) {
        RectifyEvent event = new RectifyEvent();
        event.setRectifyId(order.getId());
        event.setRectifyNo(order.getRectifyNo());
        event.setEventType(eventType);
        event.setFromStatus(fromStatus);
        event.setToStatus(toStatus);
        event.setVersionNo(order.getVersionNo());
        event.setOperatorName(operator);
        event.setEventTime(LocalDateTime.now());
        event.setDetail(detail);
        event.setSnapshot(toSnapshot(orderMapper.selectById(order.getId())));
        eventMapper.insert(event);
    }

    private String toSnapshot(RectifyOrder order) {
        try {
            return objectMapper.writeValueAsString(order);
        } catch (Exception e) {
            throw new IllegalStateException("整改单快照写不出来", e);
        }
    }

    private String normalizeType(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new BusinessException("异常类型不能为空");
        }
        String type = raw.trim().toUpperCase();
        switch (type) {
            case RectifyEnums.TYPE_EDGE_LIFT:
            case RectifyEnums.TYPE_PANEL_DAMAGE:
            case RectifyEnums.TYPE_LIGHT_FAULT:
            case RectifyEnums.TYPE_STRUCTURE_RISK:
            case RectifyEnums.TYPE_OTHER:
                return type;
            default:
                throw new BusinessException("不支持的异常类型: " + raw
                        + "（EDGE_LIFT 牌面翘边 / PANEL_DAMAGE 画面破损 / LIGHT_FAULT 亮灯故障 / STRUCTURE_RISK 结构隐患 / OTHER 其他）");
        }
    }

    private String trimTo(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("读取留证照片失败: " + file.getOriginalFilename(), e);
        }
    }
}
