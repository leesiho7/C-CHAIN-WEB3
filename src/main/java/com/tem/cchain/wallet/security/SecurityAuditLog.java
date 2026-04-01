package com.tem.cchain.wallet.security;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ════════════════════════════════════════════════════════════
 * [보안 감사 로그 엔티티] SecurityAuditLog
 * ════════════════════════════════════════════════════════════
 *
 * KMS 호출 직전에 수행되는 모든 보안 검사 과정과 결과를
 * security_audit_log 테이블에 저장합니다.
 *
 * ── 저장 시점 ───────────────────────────────────────────────
 * WithdrawalSecurityService 가 실행하는 각 검사 단계마다
 * 독립적으로 INSERT 됩니다. 하나의 출금 요청에 대해
 * 여러 개의 로그 행이 생성될 수 있습니다 (requestId로 묶음).
 *
 * ── 불변 원칙 ───────────────────────────────────────────────
 * 보안 감사 로그는 절대 UPDATE/DELETE 하지 않습니다.
 * 규제 대응 및 포렌식 분석을 위해 영구 보존합니다.
 *
 * ── 검사 유형(CheckType) ────────────────────────────────────
 * RATE_LIMIT      : 빈도 제한 검사 (Redis sliding window)
 * HIGH_AMOUNT     : 고액 출금 임계값 초과 검사
 * FDS             : 실시간 이상거래 탐지 시스템 검사
 * BLACKLIST       : 출금 대상 주소 블랙리스트 검사
 * KMS_GATE        : KMS 호출 최종 승인/차단 결과
 * OPERATOR_NOTIFY : 운영자 알림 발송 기록
 * ════════════════════════════════════════════════════════════
 */
@Entity
@Table(
    name = "security_audit_log",
    indexes = {
        @Index(name = "idx_sal_request_id",    columnList = "requestId"),
        @Index(name = "idx_sal_caller_email",  columnList = "callerEmail"),
        @Index(name = "idx_sal_created_at",    columnList = "createdAt"),
        @Index(name = "idx_sal_check_result",  columnList = "checkResult")
    }
)
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SecurityAuditLog {

    // ── 식별자 ───────────────────────────────────────────────

    /** DB 자동 증가 PK */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 요청 상관 ID (UUID).
     * 하나의 출금 요청에서 발생하는 모든 검사 로그를 묶는 키.
     * MDC(Mapped Diagnostic Context)에도 동일 값을 심어 로그 추적에 활용.
     */
    @Column(nullable = false, length = 36)
    private String requestId;

    // ── 요청자 정보 ──────────────────────────────────────────

    /** 출금 요청자 이메일 (Spring Security 세션 기준) */
    @Column(length = 100)
    private String callerEmail;

    /** 출금 요청자 IP 주소 (X-Forwarded-For 헤더 우선) */
    @Column(length = 45)
    private String ipAddress;

    /** 출금 요청자 지갑 주소 (0x...) */
    @Column(length = 42)
    private String fromAddress;

    // ── 출금 요청 내용 ───────────────────────────────────────

    /** 출금 대상 주소 (0x...) */
    @Column(nullable = false, length = 42)
    private String toAddress;

    /** 출금 토큰 종류 (OMT, ETH, USDT 등) */
    @Column(nullable = false, length = 10)
    private String tokenSymbol;

    /** 출금 요청 금액 (토큰 단위, Wei 아님) */
    @Column(nullable = false, precision = 38, scale = 18)
    private BigDecimal amount;

    // ── 검사 정보 ────────────────────────────────────────────

    /**
     * 검사 유형.
     * RATE_LIMIT / HIGH_AMOUNT / FDS / BLACKLIST / KMS_GATE / OPERATOR_NOTIFY
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CheckType checkType;

    /**
     * 검사 결과.
     * PASS    : 통과 → 다음 검사 진행
     * FAIL    : 차단 → KMS 호출 금지
     * PENDING : 승인 대기 → 운영자 승인 후 KMS 호출 허용
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CheckResult checkResult;

    /**
     * FDS 위험 점수 (0 ~ 100).
     * 0  = 정상, 100 = 확실한 이상거래.
     * FDS 외 검사는 -1 로 설정.
     */
    @Column(nullable = false)
    private int riskScore;

    /**
     * 차단/보류 사유 (자유 텍스트).
     * 예: "분당 출금 횟수 5회 초과", "고액 출금 임계값 초과 (요청 50000 OMT)"
     */
    @Column(length = 512)
    private String blockReason;

    // ── 최종 게이트 결과 ─────────────────────────────────────

    /**
     * KMS 호출 허용 여부.
     * true  = 모든 검사 통과 → KMS 서명 진행
     * false = 검사 실패 또는 대기 → KMS 호출 차단
     */
    @Column(nullable = false)
    private boolean kmsCallAllowed;

    /**
     * 운영자에게 알림을 발송했는지 여부.
     * 고액 출금 감지 또는 FDS 고위험 시 true.
     */
    @Column(nullable = false)
    private boolean operatorNotified;

    // ── 타임스탬프 ───────────────────────────────────────────

    /** 로그 생성 시각 (자동 주입, UTC 기준) */
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // ── 중첩 열거형 ──────────────────────────────────────────

    /** 보안 검사 유형 */
    public enum CheckType {
        RATE_LIMIT,       // 빈도 제한
        HIGH_AMOUNT,      // 고액 출금
        FDS,              // 이상거래 탐지
        BLACKLIST,        // 블랙리스트
        KMS_GATE,         // KMS 최종 게이트
        OPERATOR_NOTIFY   // 운영자 알림
    }

    /** 검사 결과 */
    public enum CheckResult {
        PASS,    // 통과
        FAIL,    // 차단
        PENDING  // 승인 대기
    }
}
