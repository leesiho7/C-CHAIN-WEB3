package com.tem.cchain.controller;

import com.tem.cchain.service.BybitApiService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * 거래소 연결 엔드포인트.
 *
 * ── OAuth Fast Connect 흐름 ────────────────────────────────────
 * 1. GET  /api/exchange/bybit/oauth/start     → Bybit 로그인 페이지로 redirect
 * 2. GET  /api/exchange/bybit/oauth/callback  → 인증 코드 수신, 토큰 교환 후 /exchange 로 redirect
 *
 * ── API Key 수동 연결 (폴백) ────────────────────────────────────
 * POST /api/exchange/bybit/connect  { apiKey, apiSecret }
 */
@Slf4j
@RestController
@RequestMapping("/api/exchange")
@RequiredArgsConstructor
public class ExchangeKeyController {

    private static final String OAUTH_STATE_SESSION_KEY = "bybit_oauth_state";

    private final BybitApiService bybitApiService;

    // =========================================================================
    // OAuth — Fast Connect
    // =========================================================================

    /**
     * Step 1: Bybit OAuth 인증 페이지로 리디렉트.
     * CSRF 방지용 state 를 세션에 저장 후 Bybit 로그인 URL 로 이동.
     */
    @GetMapping("/bybit/oauth/start")
    public void oauthStart(HttpSession session, HttpServletResponse response) throws Exception {
        if (!bybitApiService.isOAuthConfigured()) {
            // OAuth 미설정 시 API 키 연결 화면으로 돌아가 안내
            response.sendRedirect("/exchange?error=oauth_not_configured");
            return;
        }
        String state = UUID.randomUUID().toString();
        session.setAttribute(OAUTH_STATE_SESSION_KEY, state);
        String authUrl = bybitApiService.buildOAuthAuthorizationUrl(state);
        log.info("[Bybit OAuth] 인증 시작: state={}", state);
        response.sendRedirect(authUrl);
    }

    /**
     * Step 2: Bybit 인증 완료 후 콜백.
     * code + state 수신 → 토큰 교환 → /exchange 로 리디렉트.
     */
    @GetMapping("/bybit/oauth/callback")
    public void oauthCallback(@RequestParam String code,
                               @RequestParam String state,
                               HttpSession session,
                               HttpServletResponse response) throws Exception {
        // CSRF 검증
        String savedState = (String) session.getAttribute(OAUTH_STATE_SESSION_KEY);
        if (savedState == null || !savedState.equals(state)) {
            log.warn("[Bybit OAuth] state 불일치 — CSRF 의심");
            response.sendRedirect("/exchange?error=invalid_state");
            return;
        }
        session.removeAttribute(OAUTH_STATE_SESSION_KEY);

        String email = getEmail(session);
        if (email == null) {
            response.sendRedirect("/login?redirect=/exchange");
            return;
        }

        BybitApiService.OAuthResult result = bybitApiService.exchangeCodeAndSave(email, code);
        if (result.success()) {
            log.info("[Bybit OAuth] 연결 완료: user={}", email);
            response.sendRedirect("/exchange?connected=bybit");
        } else {
            log.warn("[Bybit OAuth] 연결 실패: {}", result.message());
            response.sendRedirect("/exchange?error=oauth_failed&msg="
                + java.net.URLEncoder.encode(result.message(), "UTF-8"));
        }
    }

    /**
     * OAuth 설정 여부 확인 (프론트에서 Fast Connect 버튼 표시 여부 결정).
     */
    @GetMapping("/bybit/oauth/available")
    public ResponseEntity<Map<String, Object>> oauthAvailable() {
        return ResponseEntity.ok(Map.of("available", bybitApiService.isOAuthConfigured()));
    }

    // =========================================================================
    // API Key — 수동 연결 (폴백)
    // =========================================================================

    @PostMapping("/bybit/connect")
    public ResponseEntity<Map<String, Object>> connect(
            @RequestBody Map<String, String> body,
            HttpSession session) {

        String email = getEmail(session);
        if (email == null)
            return ResponseEntity.status(401)
                .body(Map.of("success", false, "message", "로그인이 필요합니다"));

        String apiKey    = body.get("apiKey");
        String apiSecret = body.get("apiSecret");
        if (apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.isBlank())
            return ResponseEntity.badRequest()
                .body(Map.of("success", false, "message", "API Key와 Secret을 모두 입력해주세요"));

        BybitApiService.ConnectResult result = bybitApiService.connectAndSave(email, apiKey, apiSecret);
        return ResponseEntity.ok(Map.of("success", result.success(), "message", result.message()));
    }

    // =========================================================================
    // 잔액 / 상태 / 해제
    // =========================================================================

    @GetMapping("/bybit/balance")
    public ResponseEntity<Map<String, Object>> balance(HttpSession session) {
        String email = getEmail(session);
        if (email == null)
            return ResponseEntity.status(401)
                .body(Map.of("connected", false, "message", "로그인이 필요합니다"));

        BybitApiService.BalanceResult r = bybitApiService.getBalance(email);
        if (!r.connected())
            return ResponseEntity.ok(Map.of("connected", false, "message", r.message()));

        return ResponseEntity.ok(Map.of(
            "connected",   true,
            "success",     r.success(),
            "message",     r.message(),
            "coins",       r.coins(),
            "authMethod",  r.authMethod() != null ? r.authMethod() : "",
            "verifiedAt",  r.verifiedAt() != null ? r.verifiedAt().toString() : ""));
    }

    @GetMapping("/bybit/status")
    public ResponseEntity<Map<String, Object>> status(HttpSession session) {
        String email = getEmail(session);
        if (email == null)
            return ResponseEntity.ok(Map.of("connected", false));
        return ResponseEntity.ok(Map.of("connected", bybitApiService.isConnected(email)));
    }

    @DeleteMapping("/bybit/disconnect")
    public ResponseEntity<Map<String, Object>> disconnect(HttpSession session) {
        String email = getEmail(session);
        if (email == null)
            return ResponseEntity.status(401)
                .body(Map.of("success", false, "message", "로그인이 필요합니다"));
        bybitApiService.disconnect(email);
        return ResponseEntity.ok(Map.of("success", true, "message", "Bybit 연결이 해제됐습니다"));
    }

    // =========================================================================
    // helper
    // =========================================================================

    private String getEmail(HttpSession session) {
        Object member = session.getAttribute("member");
        if (member == null) return null;
        if (member instanceof String s) return s;
        try {
            return (String) member.getClass().getMethod("getEmail").invoke(member);
        } catch (Exception e) {
            log.warn("[ExchangeKey] 세션 이메일 추출 실패: {}", e.getMessage());
            return null;
        }
    }
}
