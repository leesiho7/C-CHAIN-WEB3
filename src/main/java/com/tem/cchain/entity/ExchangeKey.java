package com.tem.cchain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 거래소 API 키 / OAuth 토큰 저장 엔티티.
 *
 * ---- 보안 설계 ----
 * - apiSecret / accessToken / refreshToken 은 AES-256-GCM 암호화 저장
 * - 암호화 키는 환경변수 EXCHANGE_KEY_ENC_SECRET 에서만 읽음
 * - authMethod: "API_KEY" | "OAUTH"
 *
 * ---- OAuth (Bybit 브로커 파트너) ----
 * - Bybit 브로커 프로그램 가입 후 client_id/client_secret 발급
 * - https://www.bybit.com/oauth 로 사용자 리디렉트 → 인증 코드 수신
 * - accessTokenEnc + refreshTokenEnc 에 암호화 저장
 * - API_KEY 방식도 폴백으로 유지
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

    /** 연결 방식: "API_KEY" | "OAUTH" */
    @Column(name = "auth_method", nullable = false, length = 10)
    @Builder.Default
    private String authMethod = "API_KEY";

    // ── API_KEY 방식 ──────────────────────────────────────────────
    /** API Key — 평문 저장 (식별용, 비밀 아님) */
    @Column(name = "api_key", length = 100)
    private String apiKey;

    /** API Secret — AES-256-GCM 암호화 저장 */
    @Column(name = "api_secret_enc", length = 512)
    private String apiSecretEnc;

    // ── OAUTH 방식 ────────────────────────────────────────────────
    /** OAuth Access Token — AES-256-GCM 암호화 저장 */
    @Column(name = "access_token_enc", length = 1024)
    private String accessTokenEnc;

    /** OAuth Refresh Token — AES-256-GCM 암호화 저장 */
    @Column(name = "refresh_token_enc", length = 1024)
    private String refreshTokenEnc;

    /** OAuth Access Token 만료 시각 */
    @Column(name = "token_expires_at")
    private LocalDateTime tokenExpiresAt;

    // ── 공통 ──────────────────────────────────────────────────────
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

    public boolean isOAuth()  { return "OAUTH".equals(authMethod); }
    public boolean isApiKey() { return "API_KEY".equals(authMethod); }
}
