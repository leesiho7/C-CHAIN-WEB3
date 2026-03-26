package com.tem.cchain.controller;

import com.tem.cchain.service.BybitApiService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 거래소 API 키 연결/조회/해제 엔드포인트.
 *
 * 인증: 세션의 "member" 속성에서 이메일을 읽음 (Spring Security 미사용).
 */
@Slf4j
@RestController
@RequestMapping("/api/exchange")
@RequiredArgsConstructor
public class ExchangeKeyController {

    private final BybitApiService bybitApiService;

    // =========================================================================
    // Bybit 연결
    // =========================================================================

    /**
     * POST /api/exchange/bybit/connect
     * Body: { "apiKey": "...", "apiSecret": "..." }
     */
    @PostMapping("/bybit/connect")
    public ResponseEntity<Map<String, Object>> connect(
            @RequestBody Map<String, String> body,
            HttpSession session) {

        String email = getEmail(session);
        if (email == null)
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "로그인이 필요합니다"));

        String apiKey    = body.get("apiKey");
        String apiSecret = body.get("apiSecret");

        if (apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.isBlank())
            return ResponseEntity.badRequest()
                .body(Map.of("success", false, "message", "API Key와 Secret을 모두 입력해주세요"));

        BybitApiService.ConnectResult result = bybitApiService.connectAndSave(email, apiKey, apiSecret);
        return ResponseEntity.ok(Map.of("success", result.success(), "message", result.message()));
    }

    /**
     * GET /api/exchange/bybit/balance
     * 저장된 API 키로 Bybit 잔액 조회.
     */
    @GetMapping("/bybit/balance")
    public ResponseEntity<Map<String, Object>> balance(HttpSession session) {
        String email = getEmail(session);
        if (email == null)
            return ResponseEntity.status(401).body(Map.of("connected", false, "message", "로그인이 필요합니다"));

        BybitApiService.BalanceResult result = bybitApiService.getBalance(email);

        if (!result.connected())
            return ResponseEntity.ok(Map.of(
                "connected", false,
                "message",   result.message()));

        return ResponseEntity.ok(Map.of(
            "connected",   true,
            "success",     result.success(),
            "message",     result.message(),
            "coins",       result.coins(),
            "verifiedAt",  result.verifiedAt() != null ? result.verifiedAt().toString() : ""));
    }

    /**
     * DELETE /api/exchange/bybit/disconnect
     * 거래소 연결 해제.
     */
    @DeleteMapping("/bybit/disconnect")
    public ResponseEntity<Map<String, Object>> disconnect(HttpSession session) {
        String email = getEmail(session);
        if (email == null)
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "로그인이 필요합니다"));

        bybitApiService.disconnect(email);
        return ResponseEntity.ok(Map.of("success", true, "message", "Bybit 연결이 해제됐습니다"));
    }

    /**
     * GET /api/exchange/bybit/status
     * 연결 상태 확인.
     */
    @GetMapping("/bybit/status")
    public ResponseEntity<Map<String, Object>> status(HttpSession session) {
        String email = getEmail(session);
        if (email == null)
            return ResponseEntity.ok(Map.of("connected", false));

        boolean connected = bybitApiService.isConnected(email);
        return ResponseEntity.ok(Map.of("connected", connected));
    }

    // =========================================================================
    // private helper
    // =========================================================================

    private String getEmail(HttpSession session) {
        Object member = session.getAttribute("member");
        if (member == null) return null;
        // Member 엔티티 or 이메일 문자열 모두 지원
        if (member instanceof String s) return s;
        try {
            return (String) member.getClass().getMethod("getEmail").invoke(member);
        } catch (Exception e) {
            log.warn("[ExchangeKey] 세션에서 이메일 추출 실패: {}", e.getMessage());
            return null;
        }
    }
}
