package com.tem.cchain.wallet.audit;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 지갑 감사 이벤트 JPA 엔티티.
 *
 * ---- 설계 원칙 ----
 * 1. Immutable: 생성 후 수정 금지 (@Column(updatable=false))
 * 2. Never Delete: 삭제 없음, 보존 기간은 정책으로 관리
 * 3. Timestamp: UTC Instant 사용 (타임존 이슈 방지)
 * 4. Correlation ID: 분산 추적을 위한 요청 식별자
 *
 * ---- 보안 강화 옵션 ----
 * 레코드 무결성 검증이 필요하면:
 * - hash_chain 컬럼에 이전 레코드 해시를 연결 (블록체인과 유사한 구조)
 * - 이렇게 하면 중간 레코드 삭제/수정 즉시 탐지 가능
 */
@Entity
@Table(name = "wallet_audit_events",
    indexes = {
        @Index(name = "idx_audit_caller", columnList = "caller_email"),
        @Index(name = "idx_audit_occurred_at", columnList = "occurred_at"),
        @Index(name = "idx_audit_tx_hash", columnList = "tx_hash"),
        @Index(name = "idx_audit_operation", columnList = "operation")
    })
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ---- 작업 정보 ----

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, updatable = false, length = 30)
    private WalletOperation operation;

    @Column(name = "operation_result", nullable = false, updatable = false, length = 10)
    private String result;  // "SUCCESS" | "FAILURE" | "DENIED"

    // ---- 요청자 정보 ----

    @Column(name = "caller_email", updatable = false, length = 100)
    private String callerEmail;

    @Column(name = "caller_role", updatable = false, length = 20)
    private String callerRole;

    @Column(name = "caller_ip", updatable = false, length = 45)  // IPv6 대비 45자
    private String callerIp;

    // ---- 트랜잭션 정보 ----

    @Column(name = "from_address", updatable = false, length = 42)
    private String fromAddress;

    @Column(name = "to_address", updatable = false, length = 42)
    private String toAddress;

    @Column(name = "amount", updatable = false, precision = 30, scale = 18)
    private BigDecimal amount;

    @Column(name = "token_type", updatable = false, length = 10)
    private String tokenType;

    @Column(name = "tx_hash", updatable = false, length = 66)  // 0x + 64 hex
    private String txHash;

    // ---- 메타데이터 ----

    @Column(name = "correlation_id", updatable = false, length = 36)
    private String correlationId;  // UUID, MDC 추적에 사용

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "details", updatable = false, length = 500)
    private String details;  // 거부 사유, 오류 메시지 등 추가 정보

    @PrePersist
    void prePersist() {
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
    }
}
