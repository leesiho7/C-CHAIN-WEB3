package com.tem.cchain.controller;

import com.tem.cchain.entity.Member;
import com.tem.cchain.wallet.security.SecurityAuditLog;
import com.tem.cchain.wallet.security.SecurityAuditLogRepository;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 관리자 전용 보안 관제 센터 컨트롤러.
 * /admin/security 페이지 및 관련 REST API 제공.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class AdminSecurityController {

    private final SecurityAuditLogRepository securityAuditLogRepository;

    // =========================================================
    // 페이지 렌더링
    // =========================================================

    @GetMapping("/admin/security")
    public String securityPage(HttpSession session) {
        if (!isAdmin(session)) return "redirect:/login";
        return "admin-security";
    }

    // =========================================================
    // REST API - 요약 카드 데이터
    // =========================================================

    @GetMapping("/api/admin/security/summary")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getSummary(HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).build();

        LocalDateTime todayStart = LocalDateTime.now().toLocalDate().atStartOfDay();
        LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);

        // 오늘 차단된 건수 (FAIL)
        long todayBlocked = securityAuditLogRepository
                .countByCheckResultAndCreatedAtAfter(
                        SecurityAuditLog.CheckResult.FAIL, todayStart);

        // 승인 대기 건수 (PENDING)
        long pendingCount = securityAuditLogRepository
                .countByCheckResult(SecurityAuditLog.CheckResult.PENDING);

        // 고위험 FDS 탐지 건수 (7일, riskScore >= 70)
        long highRiskCount = securityAuditLogRepository
                .countHighRiskDetectionsSince(sevenDaysAgo);

        // FDS 평균 위험점수 (오늘)
        Double avgRisk = securityAuditLogRepository
                .avgFdsRiskScoreSince(SecurityAuditLog.CheckType.FDS, todayStart);

        // 차단된 고유 주소 수 (전체 기간)
        long blockedAddresses = securityAuditLogRepository
                .countDistinctBlockedToAddresses(SecurityAuditLog.CheckResult.FAIL);

        Map<String, Object> summary = new HashMap<>();
        summary.put("todayBlocked", todayBlocked);
        summary.put("pendingCount", pendingCount);
        summary.put("highRiskCount7d", highRiskCount);
        summary.put("avgRiskScore", avgRisk != null ? String.format("%.1f", avgRisk) : "0.0");
        summary.put("blockedAddresses", blockedAddresses);

        return ResponseEntity.ok(summary);
    }

    // =========================================================
    // REST API - 로그 목록 (페이징)
    // =========================================================

    @GetMapping("/api/admin/security/logs")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getLogs(
            HttpSession session,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (!isAdmin(session)) return ResponseEntity.status(403).build();

        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        var logPage = securityAuditLogRepository.findAll(pageable);

        Map<String, Object> result = new HashMap<>();
        result.put("logs", logPage.getContent());
        result.put("totalPages", logPage.getTotalPages());
        result.put("totalElements", logPage.getTotalElements());
        result.put("currentPage", page);

        return ResponseEntity.ok(result);
    }

    // =========================================================
    // REST API - 단건 상세 (팝업용)
    // =========================================================

    @GetMapping("/api/admin/security/logs/{id}")
    @ResponseBody
    public ResponseEntity<SecurityAuditLog> getLogDetail(
            HttpSession session,
            @PathVariable Long id) {
        if (!isAdmin(session)) return ResponseEntity.status(403).build();

        return securityAuditLogRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // =========================================================
    // REST API - requestId 기준 전체 체인 조회 (팝업 하단 타임라인)
    // =========================================================

    @GetMapping("/api/admin/security/chain/{requestId}")
    @ResponseBody
    public ResponseEntity<List<SecurityAuditLog>> getChain(
            HttpSession session,
            @PathVariable String requestId) {
        if (!isAdmin(session)) return ResponseEntity.status(403).build();

        return ResponseEntity.ok(
                securityAuditLogRepository.findByRequestIdOrderByCreatedAtAsc(requestId));
    }

    // =========================================================
    // private
    // =========================================================

    private boolean isAdmin(HttpSession session) {
        Member m = (Member) session.getAttribute("loginMember");
        return m != null && "admin@cchain.com".equals(m.getEmail());
    }
}
