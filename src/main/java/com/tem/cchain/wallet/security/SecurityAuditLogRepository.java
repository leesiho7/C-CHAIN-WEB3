package com.tem.cchain.wallet.security;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * security_audit_log 테이블 접근 인터페이스.
 * INSERT/SELECT 전용 — UPDATE/DELETE 쿼리 작성 금지.
 */
public interface SecurityAuditLogRepository extends JpaRepository<SecurityAuditLog, Long> {

    // ── 파생 쿼리 (Derived Query) ────────────────────────────

    /** 특정 요청 ID의 모든 검사 체인 조회 (팝업 타임라인용) */
    List<SecurityAuditLog> findByRequestIdOrderByCreatedAtAsc(String requestId);

    /** 특정 결과(FAIL/PENDING/PASS)의 전체 건수 */
    long countByCheckResult(SecurityAuditLog.CheckResult checkResult);

    /** 특정 결과 + 특정 시각 이후 건수 (오늘 차단 건수 등) */
    long countByCheckResultAndCreatedAtAfter(
            SecurityAuditLog.CheckResult checkResult,
            LocalDateTime since
    );

    // ── JPQL (파라미터 바인딩으로 enum 타입 안전 처리) ──────

    /**
     * 차단된(FAIL) 고유 출금 대상 주소 수 조회.
     * 블랙리스트 등재 주소 수 카드에 사용.
     */
    @Query("""
        SELECT COUNT(DISTINCT s.toAddress)
        FROM SecurityAuditLog s
        WHERE s.checkResult = :result
        """)
    long countDistinctBlockedToAddresses(@Param("result") SecurityAuditLog.CheckResult result);

    /**
     * FDS 검사의 평균 위험점수 조회 (riskScore > 0 인 건만).
     *
     * @param checkType FDS 타입
     * @param since     조회 시작 시각
     */
    @Query("""
        SELECT AVG(s.riskScore)
        FROM SecurityAuditLog s
        WHERE s.checkType = :checkType
          AND s.riskScore > 0
          AND s.createdAt >= :since
        """)
    Double avgFdsRiskScoreSince(
            @Param("checkType") SecurityAuditLog.CheckType checkType,
            @Param("since") LocalDateTime since
    );

    /**
     * 최근 N일 내 FDS 고위험(riskScore >= 70) 탐지 건수.
     * 요약 카드 및 BlockchainMonitoringService에서 사용.
     */
    @Query("""
        SELECT COUNT(s)
        FROM SecurityAuditLog s
        WHERE s.checkType = :checkType
          AND s.riskScore >= 70
          AND s.createdAt >= :since
        """)
    long countHighRiskDetectionsSince(
            @Param("checkType") SecurityAuditLog.CheckType checkType,
            @Param("since") LocalDateTime since
    );

    /**
     * 최근 N일 내 FDS 고위험 탐지 건수 (checkType 고정 오버로드).
     * BlockchainMonitoringService.monitorDepositFlowAnomalies()에서 호출.
     */
    default long countHighRiskDetectionsSince(LocalDateTime since) {
        return countHighRiskDetectionsSince(SecurityAuditLog.CheckType.FDS, since);
    }

    /**
     * 특정 대상 주소로의 최근 출금 KMS_GATE 이력 조회.
     * FDS RULE-F03 (반복 출금) 탐지에 사용.
     */
    @Query("""
        SELECT s FROM SecurityAuditLog s
        WHERE s.toAddress = :toAddress
          AND s.checkType = :checkType
          AND s.createdAt >= :since
        ORDER BY s.createdAt DESC
        """)
    List<SecurityAuditLog> findRecentTransfersByToAddress(
            @Param("toAddress") String toAddress,
            @Param("checkType") SecurityAuditLog.CheckType checkType,
            @Param("since") LocalDateTime since
    );

    /**
     * 특정 이메일의 최근 FAIL 시도 횟수.
     * FDS RULE-F04 (평판 검사)에 사용.
     */
    @Query("""
        SELECT COUNT(s)
        FROM SecurityAuditLog s
        WHERE s.callerEmail = :callerEmail
          AND s.checkResult = :result
          AND s.createdAt >= :since
        """)
    long countFailedAttemptsSince(
            @Param("callerEmail") String callerEmail,
            @Param("result") SecurityAuditLog.CheckResult result,
            @Param("since") LocalDateTime since
    );

    /** countFailedAttemptsSince FAIL 고정 오버로드 (RealTimeFdsService 호환) */
    default long countFailedAttemptsSince(String callerEmail, LocalDateTime since) {
        return countFailedAttemptsSince(callerEmail, SecurityAuditLog.CheckResult.FAIL, since);
    }

    /** findRecentTransfersByToAddress KMS_GATE 고정 오버로드 (RealTimeFdsService 호환) */
    default List<SecurityAuditLog> findRecentTransfersByToAddress(String toAddress, LocalDateTime since) {
        return findRecentTransfersByToAddress(toAddress, SecurityAuditLog.CheckType.KMS_GATE, since);
    }
}
