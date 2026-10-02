package com.evops.rectify.controller;

import com.evops.common.ApiResponse;
import com.evops.rectify.dto.DispatchRequest;
import com.evops.rectify.dto.OverdueScanResult;
import com.evops.rectify.dto.ReportRequest;
import com.evops.rectify.dto.RectifyDetailView;
import com.evops.rectify.dto.ReturnRequest;
import com.evops.rectify.dto.ReviewRequest;
import com.evops.rectify.entity.RectifyOrder;
import com.evops.rectify.entity.RectifyPhoto;
import com.evops.rectify.service.RectifyService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 异常上报整改线：
 * 巡查员现场上报（照片可选）→ 系统派单给维护商 → 处理回传（照片可选）→ 复勘通过销项 / 驳回重处；
 * 逾期自动逐级抄送；照片关闭前不许删（无删除接口）；点位档案可翻历史异常。
 */
@RestController
@RequestMapping("/api/rectifies")
public class RectifyController {

    private final RectifyService rectifyService;

    public RectifyController(RectifyService rectifyService) {
        this.rectifyService = rectifyService;
    }

    /** 巡查员现场提交异常：multipart 表单，photos 可传 0~N 张。 */
    @PostMapping
    public ApiResponse<RectifyOrder> report(
            @RequestParam("siteNo") String siteNo,
            @RequestParam("issueType") String issueType,
            @RequestParam(value = "issueDesc", required = false) String issueDesc,
            @RequestParam(value = "severity", required = false) String severity,
            @RequestParam(value = "deadlineHours", required = false) Integer deadlineHours,
            @RequestParam(value = "operatorName", required = false) String operatorName,
            @RequestParam(value = "remark", required = false) String remark,
            @RequestParam(value = "photos", required = false) List<MultipartFile> photos) {
        ReportRequest request = new ReportRequest();
        request.setSiteNo(siteNo);
        request.setIssueType(issueType);
        request.setIssueDesc(issueDesc);
        request.setSeverity(severity);
        request.setDeadlineHours(deadlineHours);
        request.setOperatorName(operatorName);
        request.setRemark(remark);
        return ApiResponse.ok(rectifyService.report(request, photos));
    }

    /** 异常单翻查：点位 / 状态 / 单号 / 只看逾期。 */
    @GetMapping
    public ApiResponse<List<RectifyOrder>> list(@RequestParam(required = false) String siteNo,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(required = false) String rectifyNo,
                                                @RequestParam(required = false) Boolean overdueOnly) {
        return ApiResponse.ok(rectifyService.search(siteNo, status, rectifyNo, overdueOnly));
    }

    /** 点位档案视角：该点位全部历史异常（含已销项）。 */
    @GetMapping("/sites/{siteNo}/history")
    public ApiResponse<List<RectifyOrder>> siteHistory(@PathVariable String siteNo) {
        return ApiResponse.ok(rectifyService.listSiteHistory(siteNo));
    }

    /** 手动触发一次逾期扫描（平时由定时任务自动跑）。 */
    @GetMapping("/overdue/scan")
    public ApiResponse<OverdueScanResult> scanOverdue() {
        return ApiResponse.ok(rectifyService.scanOverdue());
    }

    /** 异常单全档：主档 + 留证照片 + 逐动作事件 + 抄送记录。 */
    @GetMapping("/{rectifyNo}")
    public ApiResponse<RectifyDetailView> detail(@PathVariable String rectifyNo) {
        return ApiResponse.ok(rectifyService.detail(rectifyNo));
    }

    /** 系统派单给维护商。 */
    @PostMapping("/{rectifyNo}/dispatch")
    public ApiResponse<RectifyOrder> dispatch(@PathVariable String rectifyNo,
                                              @Valid @RequestBody DispatchRequest request) {
        return ApiResponse.ok(rectifyService.dispatch(rectifyNo, request));
    }

    /** 维护商处理完回传：multipart 表单，整改后照片可传 0~N 张。 */
    @PostMapping("/{rectifyNo}/return")
    public ApiResponse<RectifyOrder> vendorReturn(
            @PathVariable String rectifyNo,
            @RequestParam("finishNote") String finishNote,
            @RequestParam(value = "operatorName", required = false) String operatorName,
            @RequestParam(value = "remark", required = false) String remark,
            @RequestParam(value = "photos", required = false) List<MultipartFile> photos) {
        ReturnRequest request = new ReturnRequest();
        request.setFinishNote(finishNote);
        request.setOperatorName(operatorName);
        request.setRemark(remark);
        return ApiResponse.ok(rectifyService.vendorReturn(rectifyNo, request, photos));
    }

    /** 复勘通过：唯一销项路径。 */
    @PostMapping("/{rectifyNo}/review-pass")
    public ApiResponse<RectifyOrder> reviewPass(@PathVariable String rectifyNo,
                                                @RequestBody(required = false) ReviewRequest request) {
        return ApiResponse.ok(rectifyService.reviewPass(rectifyNo,
                request == null ? new ReviewRequest() : request));
    }

    /** 复勘驳回：必须带不通过原因，退回维护商重处。 */
    @PostMapping("/{rectifyNo}/review-reject")
    public ApiResponse<RectifyOrder> reviewReject(@PathVariable String rectifyNo,
                                                  @Valid @RequestBody ReviewRequest request) {
        return ApiResponse.ok(rectifyService.reviewReject(rectifyNo, request));
    }

    /**
     * 销项后按留存策略归档留证照片；未销项调用直接拒（关闭前一张都不许删）。
     * 默认不调用——照片随单完整留存。
     */
    @PostMapping("/{rectifyNo}/archive-photos")
    public ApiResponse<Integer> archivePhotos(@PathVariable String rectifyNo,
                                              @RequestParam(required = false) String operatorName) {
        return ApiResponse.ok(rectifyService.archivePhotosAfterClose(rectifyNo, operatorName));
    }

    /** 留证照片下载（带内联预览支持，供复勘人核对现场）。 */
    @GetMapping("/photos/{photoId}/download")
    public ResponseEntity<ByteArrayResource> downloadPhoto(@PathVariable Long photoId,
                                                          @RequestParam(value = "inline", required = false) Boolean inline) {
        RectifyPhoto photo = rectifyService.requirePhoto(photoId);
        byte[] data = rectifyService.readPhoto(photo);
        MediaType mediaType = photo.getContentType() != null
                ? MediaType.parseMediaType(photo.getContentType()) : MediaType.APPLICATION_OCTET_STREAM;
        String dispositionType = Boolean.TRUE.equals(inline) ? "inline" : "attachment";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(dispositionType, photo.getFileName()))
                .contentType(mediaType)
                .contentLength(data.length)
                .body(new ByteArrayResource(data));
    }

    private String contentDisposition(String type, String filename) {
        String safe = filename == null ? "photo.jpg" : filename.replaceAll("[\"\\\\]", "_");
        try {
            String encoded = URLEncoder.encode(safe, StandardCharsets.UTF_8.name()).replace("+", "%20");
            return type + "; filename=\"" + safe + "\"; filename*=UTF-8''" + encoded;
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 不受支持", e);
        }
    }
}
