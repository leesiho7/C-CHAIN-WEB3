package com.tem.cchain.wallet.security;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 보안 감사 로그 통계 및 페이징 조회 서비스.
 * AdminSecurityController 전용 — 읽기 전용(readOnly=true).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecurityAuditService {

    private final SecurityAuditLogRepository repository;

    // =========================================================
    // 1. 요약 카드 통계
    // =========================================================

    /**
     * 대시보드 상단 5개 카드에 필요한 통계를 한 번에 계산해 반환합니다.
     *
     * ── 계산 항목 ──────────────────────────────────────────
     * todayBlocked     : 오늘 00:00 이후 FAIL 건수
     * pendingCount     : 전체 PENDING(승인 대기) 건수
     * blockedAddresses : FAIL 결과의 고유 대상 주소 수
     * highRiskCount7d  : 최근 7일 FDS riskScore >= 70 건수
     * avgRiskScore     : 오늘 FDS 검사 평균 위험점수
     */
    @Transactional(readOnly = true)
    public SummaryDto getSummary() {
        LocalDateTime todayStart    = LocalDateTime.now().toLocalDate().atStartOfDay();
        LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);

        long todayBlocked = repository.countByCheckResultAndCreatedAtAfter(
                SecurityAuditLog.CheckResult.FAIL, todayStart);

        long pendingCount = repository.countByCheckResult(
                SecurityAuditLog.CheckResult.PENDING);

        long blockedAddresses = repository.countDistinctBlockedToAddresses(
                SecurityAuditLog.CheckResult.FAIL);

        long highRiskCount7d = repository.countHighRiskDetectionsSince(
                SecurityAuditLog.CheckType.FDS, sevenDaysAgo);

        Double avgRaw = repository.avgFdsRiskScoreSince(
                SecurityAuditLog.CheckType.FDS, todayStart);
        String avgRiskScore = avgRaw != null
                ? String.format("%.1f", avgRaw)
                : "0.0";

        return SummaryDto.builder()
                .todayBlocked(todayBlocked)
                .pendingCount(pendingCount)
                .blockedAddresses(blockedAddresses)
                .highRiskCount7d(highRiskCount7d)
                .avgRiskScore(avgRiskScore)
                .build();
    }

    // =========================================================
    // 2. 페이징 로그 조회
    // =========================================================

    /**
     * 최신 순으로 감사 로그를 페이징하여 반환합니다.
     *
     * @param page 0-based 페이지 번호
     * @param size 페이지 크기 (기본 20)
     */
    @Transactional(readOnly = true)
    public Page<SecurityAuditLog> getLogs(int page, int size) {
        PageRequest pageable = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Direction.DESC, "createdAt")
        );
        return repository.findAll(pageable);
    }

    // =========================================================
    // 3. 요약 DTO
    // =========================================================

    @Getter
    @Builder
    public static class SummaryDto {
        private final long   todayBlocked;     // 오늘 차단 건수
        private final long   pendingCount;     // 승인 대기 건수
        private final long   blockedAddresses; // 고유 차단 주소 수
        private final long   highRiskCount7d;  // 7일 고위험 탐지
        private final String avgRiskScore;     // FDS 평균 위험점수 (소수점 1자리)
    }
}
