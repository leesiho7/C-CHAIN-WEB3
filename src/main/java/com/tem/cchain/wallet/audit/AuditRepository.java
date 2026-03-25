package com.tem.cchain.wallet.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 감사 이벤트 저장소.
 * 조회용 쿼리 메서드들을 포함합니다.
 */
public interface AuditRepository extends JpaRepository<AuditEvent, Long> {

    /**
     * 특정 사용자의 오늘 누적 전송량 조회.
     * PolicyEngine이 일일 한도 검사 시 사용합니다.
     */
    @Query("""
        SELECT COALESCE(SUM(a.amount), 0)
        FROM AuditEvent a
        WHERE a.callerEmail = :email
          AND a.operation IN ('MASTER_TRANSFER', 'REWARD_PAYMENT')
          AND a.result = 'SUCCESS'
          AND a.occurredAt >= :startOfDay
        """)
    BigDecimal sumSuccessfulTransfersSince(
        @Param("email") String email,
        @Param("startOfDay") Instant startOfDay
    );

    /**
     * 특정 주소로의 최근 전송 이력 조회.
     */
    List<AuditEvent> findTop10ByToAddressOrderByOccurredAtDesc(String toAddress);

    /**
     * 역할 거부 이벤트 조회 (보안 모니터링용).
     */
    List<AuditEvent> findByOperationAndOccurredAtAfter(WalletOperation operation, Instant after);

    /**
     * 특정 txHash로 감사 이벤트 조회.
     */
    java.util.Optional<AuditEvent> findByTxHash(String txHash);
}
