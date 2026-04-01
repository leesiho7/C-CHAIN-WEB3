package com.tem.cchain.wallet.security;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ════════════════════════════════════════════════════════════
 * [보안 감사 로그 레포지토리] SecurityAuditLogRepository
 * ════════════════════════════════════════════════════════════
 *
 * security_audit_log 테이블 접근 인터페이스.
 *
 * ── 설계 원칙 ───────────────────────────────────────────────
 * - INSERT 전용. UPDATE/DELETE 쿼리 절대 작성 금지.
 * - 빈도 제한 카운트 조회는 Redis에서 처리.
 *   이 레포지토리는 감사/분석 목적의 조회만 담당.
 * ════════════════════════════════════════════════════════════
 */
public interface SecurityAuditLogRepository extends JpaRepository<SecurityAuditLog, Long> {

    /**
     * 특정 요청 ID에 해당하는 모든 검사 로그 조회.
     * 하나의 출금 요청에 대한 전체 검사 흐름을 재현할 때 사용.
     *
     * @param requestId 출금 요청 상관 ID (UUID)
     */
    List<SecurityAuditLog> findByRequestIdOrderByCreatedAtAsc(String requestId);

    /**
     * 특정 이메일의 최근 N시간 내 차단된 출금 횟수 조회.
     * FDS 분석 및 이상 패턴 탐지에 활용.
     *
     * @param callerEmail 요청자 이메일
     * @param since       조회 시작 시각
     */
    @Query("""
        SELECT COUNT(s) FROM SecurityAuditLog s
        WHERE s.callerEmail = :callerEmail
          AND s.checkResult = 'FAIL'
          AND s.createdAt >= :since
        """)
    long countFailedAttemptsSince(
        @Param("callerEmail") String callerEmail,
        @Param("since") LocalDateTime since
    );

    /**
     * 특정 대상 주소로의 최근 출금 시도 이력 조회.
     * 동일 주소 반복 출금 패턴 탐지에 활용.
     *
     * @param toAddress 출금 대상 주소
     * @param since     조회 시작 시각
     */
    @Query("""
        SELECT s FROM SecurityAuditLog s
        WHERE s.toAddress = :toAddress
          AND s.checkType = 'KMS_GATE'
          AND s.createdAt >= :since
        ORDER BY s.createdAt DESC
        """)
    List<SecurityAuditLog> findRecentTransfersByToAddress(
        @Param("toAddress") String toAddress,
        @Param("since") LocalDateTime since
    );

    /**
     * 미승인 상태(PENDING)인 출금 요청 목록 조회.
     * 운영자 승인 대기 화면에서 사용.
     */
    @Query("""
        SELECT s FROM SecurityAuditLog s
        WHERE s.checkResult = 'PENDING'
          AND s.checkType = 'KMS_GATE'
        ORDER BY s.createdAt DESC
        """)
    List<SecurityAuditLog> findPendingApprovals();

    /**
     * 특정 기간 내 FDS 고위험(riskScore >= 70) 탐지 건수 조회.
     * 운영 대시보드 통계용.
     *
     * @param since 조회 시작 시각
     */
    @Query("""
        SELECT COUNT(s) FROM SecurityAuditLog s
        WHERE s.checkType = 'FDS'
          AND s.riskScore >= 70
          AND s.createdAt >= :since
        """)
    long countHighRiskDetectionsSince(@Param("since") LocalDateTime since);
}
