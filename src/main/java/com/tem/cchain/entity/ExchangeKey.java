package com.tem.cchain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 거래소 API 키 저장 엔티티.
 *
 * ---- 보안 설계 ----
 * - apiSecret 은 AES-256-GCM 으로 암호화해서 저장 (평문 절대 금지)
 * - 암호화 키는 환경변수 EXCHANGE_KEY_ENC_SECRET 에서만 읽음
 * - apiKey 는 식별용으로 평문 저장 (Bybit 화면에서도 보임)
 * - API Secret 은 저장 후 다시 조회할 수 없음 (단방향 운영)
 */
@Entity
@Table(name = "exchange_keys",
       uniqueConstraints = @UniqueConstraint(columnNames = {"member_email", "exchange"}))
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExchangeKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_email", nullable = false, length = 100)
    private String memberEmail;

    /** 거래소 식별자 (예: "BYBIT") */
    @Column(nullable = false, length = 20)
    private String exchange;

    /** API Key — 평문 저장 (식별용, 비밀 아님) */
    @Column(name = "api_key", nullable = false, length = 100)
    private String apiKey;

    /** API Secret — AES-256-GCM 암호화 저장 */
    @Column(name = "api_secret_enc", nullable = false, length = 512)
    private String apiSecretEnc;

    /** 마지막 연결 검증 시각 */
    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    /** 연결 상태 (ACTIVE / INVALID / REVOKED) */
    @Column(nullable = false, length = 10)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() { this.createdAt = LocalDateTime.now(); }
}
