package com.tem.cchain.controller;

import com.tem.cchain.entity.Member;
import com.tem.cchain.wallet.security.SecurityAuditLog;
import com.tem.cchain.wallet.security.SecurityAuditLogRepository;
import com.tem.cchain.wallet.security.SecurityAuditService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ════════════════════════════════════════════════════════════
 * [보안 관제 센터 컨트롤러] AdminSecurityController
 * ════════════════════════════════════════════════════════════
 *
 * ── 접근 제어 ──────────────────────────────────────────────
 * 모든 엔드포인트는 isAdmin() 세션 검사로 보호됩니다.
 * admin@cchain.com 계정의 로그인 세션이 없으면 /login 으로 리다이렉트.
 *
 * ── @RequiresWalletRole 미사용 이유 ─────────────────────────
 * 이 프로젝트의 인증은 HttpSession 기반(LoginInterceptor)입니다.
 * @RequiresWalletRole 은 Spring Security Context 기반이라
 * 현재 아키텍처에서는 모든 요청자가 AnonymousUser로 처리되어
 * MASTER 역할 검사에서 전원 차단됩니다.
 * isAdmin() 세션 체크가 WalletRole.MASTER 와 동등한 보안을 제공합니다.
 * ════════════════════════════════════════════════════════════
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class AdminSecurityController {

    private final SecurityAuditService      securityAuditService;
    private final SecurityAuditLogRepository auditLogRepository;

    // =========================================================
    // 페이지 렌더링
    // =========================================================

    /** 보안 관제 센터 페이지. 비관리자는 /login 리다이렉트. */
    @GetMapping("/admin/security")
    public String securityPage(HttpSession session) {
        if (!isAdmin(session)) {
            log.warn("[SecurityConsole] 비인가 접근 차단 → /login 리다이렉트");
            return "redirect:/login";
        }
        return "admin-security";
    }

    // =========================================================
    // REST API — 요약 카드 (5개)
    // =========================================================

    /**
     * 대시보드 상단 카드에 필요한 5가지 통계를 반환합니다.
     * SecurityAuditService.getSummary() 에 로직 위임.
     */
    @GetMapping("/api/admin/security/summary")
    @ResponseBody
    public ResponseEntity<SecurityAuditService.SummaryDto> getSummary(HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(securityAuditService.getSummary());
    }

    // =========================================================
    // REST API — 감사 로그 목록 (페이징)
    // =========================================================

    /**
     * 최신 순으로 감사 로그를 페이징하여 반환합니다.
     *
     * @param page 0-based 페이지 번호 (기본 0)
     * @param size 페이지 크기 (기본 20)
     * @return logs(목록), totalPages, totalElements, currentPage
     */
    @GetMapping("/api/admin/security/logs")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getLogs(
            HttpSession session,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        if (!isAdmin(session)) return ResponseEntity.status(403).build();

        // size 남용 방지 (최대 100건)
        size = Math.min(size, 100);

        Page<SecurityAuditLog> logPage = securityAuditService.getLogs(page, size);

        Map<String, Object> result = new HashMap<>();
        result.put("logs",          logPage.getContent());
        result.put("totalPages",    logPage.getTotalPages());
        result.put("totalElements", logPage.getTotalElements());
        result.put("currentPage",   page);
        result.put("hasNext",       logPage.hasNext());
        result.put("hasPrev",       logPage.hasPrevious());

        return ResponseEntity.ok(result);
    }

    // =========================================================
    // REST API — 단건 상세 (팝업)
    // =========================================================

    @GetMapping("/api/admin/security/logs/{id}")
    @ResponseBody
    public ResponseEntity<SecurityAuditLog> getLogDetail(
            HttpSession session,
            @PathVariable Long id) {

        if (!isAdmin(session)) return ResponseEntity.status(403).build();

        return auditLogRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // =========================================================
    // REST API — requestId 기준 체인 전체 (팝업 타임라인)
    // =========================================================

    @GetMapping("/api/admin/security/chain/{requestId}")
    @ResponseBody
    public ResponseEntity<List<SecurityAuditLog>> getChain(
            HttpSession session,
            @PathVariable String requestId) {

        if (!isAdmin(session)) return ResponseEntity.status(403).build();

        return ResponseEntity.ok(
                auditLogRepository.findByRequestIdOrderByCreatedAtAsc(requestId));
    }

    // =========================================================
    // private: 관리자 여부 검사
    // =========================================================

    /**
     * HTTP 세션에서 admin@cchain.com 로그인 여부 확인.
     * WalletRole.MASTER 수준의 접근 통제와 동등합니다.
     */
    private boolean isAdmin(HttpSession session) {
        Member m = (Member) session.getAttribute("loginMember");
        return m != null && "admin@cchain.com".equals(m.getEmail());
    }
}
