package com.tem.cchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tem.cchain.entity.ExchangeKey;
import com.tem.cchain.repository.ExchangeKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Bybit V5 REST API 연동 서비스.
 *
 * ── 인증 방식 ──────────────────────────────────────────────────
 * 1. OAUTH  : Bearer {access_token} 헤더 사용 (Bybit 브로커 파트너 전용)
 * 2. API_KEY: HMAC-SHA256 서명 (기존 방식, 폴백)
 *
 * ── Bybit OAuth 브로커 프로그램 ────────────────────────────────
 * - 가입: https://partner.bybit.com/
 * - 가입 승인 후 client_id / client_secret 발급
 * - 환경변수: BYBIT_OAUTH_CLIENT_ID, BYBIT_OAUTH_CLIENT_SECRET
 * ──────────────────────────────────────────────────────────────
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BybitApiService {

    private static final String BYBIT_BASE_URL   = "https://api.bybit.com";
    private static final String BYBIT_OAUTH_URL  = "https://www.bybit.com/oauth";
    private static final String BYBIT_TOKEN_URL  = BYBIT_BASE_URL + "/v5/oauth/token";
    private static final int    RECV_WINDOW      = 5000;
    private static final String AES_ALGO         = "AES/GCM/NoPadding";
    private static final int    GCM_IV_LEN       = 12;
    private static final int    GCM_TAG_LEN      = 128;

    private final ExchangeKeyRepository exchangeKeyRepository;
    private final ObjectMapper          objectMapper = new ObjectMapper();
    private final HttpClient            httpClient   = HttpClient.newHttpClient();

    @Value("${exchange.key.enc.secret:}")
    private String encSecret;

    @Value("${bybit.oauth.client-id:}")
    private String oauthClientId;

    @Value("${bybit.oauth.client-secret:}")
    private String oauthClientSecret;

    @Value("${bybit.oauth.redirect-uri:}")
    private String oauthRedirectUri;

    // =========================================================================
    // OAuth — Fast Connect
    // =========================================================================

    /**
     * OAuth 인증 URL 생성 (사용자를 Bybit 로그인 페이지로 리디렉트).
     * state 값은 세션에 저장해 CSRF 방지에 사용.
     */
    public String buildOAuthAuthorizationUrl(String state) {
        String scope = "openid account.balance.read";
        return BYBIT_OAUTH_URL
            + "?client_id="    + encode(oauthClientId)
            + "&redirect_uri=" + encode(oauthRedirectUri)
            + "&response_type=code"
            + "&scope="        + encode(scope)
            + "&state="        + encode(state);
    }

    /**
     * OAuth 인증 코드 → 액세스 토큰 교환 후 DB 저장.
     */
    @Transactional
    public OAuthResult exchangeCodeAndSave(String memberEmail, String code) {
        try {
            // 1. 토큰 교환
            String body = "grant_type=authorization_code"
                + "&code="         + encode(code)
                + "&redirect_uri=" + encode(oauthRedirectUri)
                + "&client_id="    + encode(oauthClientId)
                + "&client_secret="+ encode(oauthClientSecret);

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BYBIT_TOKEN_URL))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode json = objectMapper.readTree(resp.body());

            // Bybit 토큰 응답: retCode 0 = 성공
            if (json.has("retCode") && json.path("retCode").asInt(-1) != 0) {
                return OAuthResult.fail(json.path("retMsg").asText("토큰 교환 실패"));
            }

            String accessToken  = json.path("access_token").asText("");
            String refreshToken = json.path("refresh_token").asText("");
            int    expiresIn    = json.path("expires_in").asInt(7200); // 기본 2시간

            if (accessToken.isBlank())
                return OAuthResult.fail("액세스 토큰을 받지 못했습니다");

            // 2. 암호화 후 DB upsert
            ExchangeKey key = exchangeKeyRepository
                .findByMemberEmailAndExchange(memberEmail, "BYBIT")
                .orElse(ExchangeKey.builder()
                    .memberEmail(memberEmail)
                    .exchange("BYBIT")
                    .build());

            key.setAuthMethod("OAUTH");
            key.setAccessTokenEnc(encrypt(accessToken));
            key.setRefreshTokenEnc(refreshToken.isBlank() ? null : encrypt(refreshToken));
            key.setTokenExpiresAt(LocalDateTime.now().plusSeconds(expiresIn - 60));
            key.setStatus("ACTIVE");
            key.setVerifiedAt(LocalDateTime.now());
            // API 키 방식 필드는 지우지 않음 (혹시 남아 있을 경우)
            exchangeKeyRepository.save(key);

            log.info("[Bybit OAuth] 연결 성공: user={}", memberEmail);
            return OAuthResult.ok();

        } catch (Exception e) {
            log.error("[Bybit OAuth] 토큰 교환 오류: {}", e.getMessage());
            return OAuthResult.fail("인증 처리 오류: " + e.getMessage());
        }
    }

    /** OAuth 설정(client_id)이 환경변수에 세팅되어 있는지 확인 */
    public boolean isOAuthConfigured() {
        return oauthClientId != null && !oauthClientId.isBlank()
            && oauthClientSecret != null && !oauthClientSecret.isBlank();
    }

    // =========================================================================
    // API Key — 수동 연결 (폴백)
    // =========================================================================

    @Transactional
    public ConnectResult connectAndSave(String memberEmail, String apiKey, String apiSecret) {
        try {
            JsonNode walletNode = callBybitWithApiKey("/v5/account/wallet-balance",
                "accountType=UNIFIED", apiKey, apiSecret);

            if (walletNode.path("retCode").asInt(-1) != 0)
                return ConnectResult.fail(walletNode.path("retMsg").asText("API 키 검증 실패"));

            ExchangeKey key = exchangeKeyRepository
                .findByMemberEmailAndExchange(memberEmail, "BYBIT")
                .orElse(ExchangeKey.builder()
                    .memberEmail(memberEmail)
                    .exchange("BYBIT")
                    .build());

            key.setAuthMethod("API_KEY");
            key.setApiKey(apiKey);
            key.setApiSecretEnc(encrypt(apiSecret));
            key.setStatus("ACTIVE");
            key.setVerifiedAt(LocalDateTime.now());
            exchangeKeyRepository.save(key);

            log.info("[Bybit API Key] 연결 성공: user={}", memberEmail);
            return ConnectResult.ok();

        } catch (Exception e) {
            log.error("[Bybit API Key] 연결 오류: {}", e.getMessage());
            return ConnectResult.fail("Bybit 서버 연결 오류: " + e.getMessage());
        }
    }

    // =========================================================================
    // 잔액 조회 (OAuth / API_KEY 자동 선택)
    // =========================================================================

    public BalanceResult getBalance(String memberEmail) {
        ExchangeKey key = exchangeKeyRepository
            .findByMemberEmailAndExchange(memberEmail, "BYBIT")
            .orElse(null);

        if (key == null || !"ACTIVE".equals(key.getStatus()))
            return BalanceResult.notConnected();

        try {
            JsonNode resp;
            if (key.isOAuth()) {
                resp = callBybitWithBearer("/v5/account/wallet-balance",
                    "accountType=UNIFIED", key);
            } else {
                String secret = decrypt(key.getApiSecretEnc());
                resp = callBybitWithApiKey("/v5/account/wallet-balance",
                    "accountType=UNIFIED", key.getApiKey(), secret);
            }

            if (resp.path("retCode").asInt(-1) != 0)
                return BalanceResult.error(resp.path("retMsg").asText("잔액 조회 실패"));

            List<Map<String, String>> coins = new ArrayList<>();
            JsonNode list = resp.path("result").path("list");
            if (list.isArray()) {
                for (JsonNode account : list) {
                    JsonNode coinList = account.path("coin");
                    if (coinList.isArray()) {
                        for (JsonNode coin : coinList) {
                            double bal = coin.path("walletBalance").asDouble(0);
                            if (bal > 0) {
                                Map<String, String> entry = new LinkedHashMap<>();
                                entry.put("coin",     coin.path("coin").asText());
                                entry.put("balance",  coin.path("walletBalance").asText("0"));
                                entry.put("usdValue", coin.path("usdValue").asText("0"));
                                coins.add(entry);
                            }
                        }
                    }
                }
            }
            return BalanceResult.ok(coins, key.getVerifiedAt(), key.getAuthMethod());

        } catch (Exception e) {
            log.error("[Bybit] 잔액 조회 오류: {}", e.getMessage());
            return BalanceResult.error("잔액 조회 오류: " + e.getMessage());
        }
    }

    @Transactional
    public void disconnect(String memberEmail) {
        exchangeKeyRepository.deleteByMemberEmailAndExchange(memberEmail, "BYBIT");
        log.info("[Bybit] 연결 해제: user={}", memberEmail);
    }

    public boolean isConnected(String memberEmail) {
        return exchangeKeyRepository
            .findByMemberEmailAndExchange(memberEmail, "BYBIT")
            .map(k -> "ACTIVE".equals(k.getStatus()))
            .orElse(false);
    }

    // =========================================================================
    // HTTP — Bearer (OAuth)
    // =========================================================================

    private JsonNode callBybitWithBearer(String path, String queryString,
                                          ExchangeKey key) throws Exception {
        // 토큰 만료 체크 → 리프레시
        String accessToken = getValidAccessToken(key);

        String url = BYBIT_BASE_URL + path + (queryString.isEmpty() ? "" : "?" + queryString);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .header("Authorization",  "Bearer " + accessToken)
            .header("Content-Type",   "application/json")
            .build();

        HttpResponse<String> response = httpClient.send(request,
            HttpResponse.BodyHandlers.ofString());
        return objectMapper.readTree(response.body());
    }

    private String getValidAccessToken(ExchangeKey key) throws Exception {
        // 만료 전이면 그냥 사용
        if (key.getTokenExpiresAt() != null
                && LocalDateTime.now().isBefore(key.getTokenExpiresAt())) {
            return decrypt(key.getAccessTokenEnc());
        }
        // 만료됐으면 리프레시
        return refreshAccessToken(key);
    }

    @Transactional
    private String refreshAccessToken(ExchangeKey key) throws Exception {
        if (key.getRefreshTokenEnc() == null)
            throw new IllegalStateException("리프레시 토큰 없음 — 재인증 필요");

        String refreshToken = decrypt(key.getRefreshTokenEnc());
        String body = "grant_type=refresh_token"
            + "&refresh_token=" + encode(refreshToken)
            + "&client_id="     + encode(oauthClientId)
            + "&client_secret=" + encode(oauthClientSecret);

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BYBIT_TOKEN_URL))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode json = objectMapper.readTree(resp.body());

        String newToken = json.path("access_token").asText("");
        if (newToken.isBlank())
            throw new IllegalStateException("토큰 리프레시 실패: " + json.path("retMsg").asText());

        int expiresIn = json.path("expires_in").asInt(7200);
        key.setAccessTokenEnc(encrypt(newToken));
        key.setTokenExpiresAt(LocalDateTime.now().plusSeconds(expiresIn - 60));
        if (!json.path("refresh_token").asText("").isBlank())
            key.setRefreshTokenEnc(encrypt(json.path("refresh_token").asText()));
        exchangeKeyRepository.save(key);

        return newToken;
    }

    // =========================================================================
    // HTTP — HMAC-SHA256 (API_KEY)
    // =========================================================================

    private JsonNode callBybitWithApiKey(String path, String queryString,
                                          String apiKey, String apiSecret) throws Exception {
        long   timestamp = System.currentTimeMillis();
        String paramStr  = timestamp + apiKey + RECV_WINDOW + queryString;
        String signature = hmacSha256(paramStr, apiSecret);

        String url = BYBIT_BASE_URL + path + (queryString.isEmpty() ? "" : "?" + queryString);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .header("X-BAPI-API-KEY",     apiKey)
            .header("X-BAPI-TIMESTAMP",   String.valueOf(timestamp))
            .header("X-BAPI-SIGN",        signature)
            .header("X-BAPI-RECV-WINDOW", String.valueOf(RECV_WINDOW))
            .header("Content-Type",       "application/json")
            .build();

        HttpResponse<String> response = httpClient.send(request,
            HttpResponse.BodyHandlers.ofString());
        return objectMapper.readTree(response.body());
    }

    private String hmacSha256(String data, String key) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
            key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : raw) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // =========================================================================
    // AES-256-GCM
    // =========================================================================

    private javax.crypto.SecretKey getEncKey() throws Exception {
        if (encSecret == null || encSecret.isBlank())
            throw new IllegalStateException("EXCHANGE_KEY_ENC_SECRET 환경변수가 설정되지 않았습니다");
        byte[] keyBytes = Arrays.copyOf(encSecret.getBytes(StandardCharsets.UTF_8), 32);
        return new SecretKeySpec(keyBytes, "AES");
    }

    public String encrypt(String plaintext) throws Exception {
        javax.crypto.SecretKey key = getEncKey();
        byte[] iv = new byte[GCM_IV_LEN];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance(AES_ALGO);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LEN, iv));
        byte[] enc = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + enc.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(enc, 0, combined, iv.length, enc.length);
        return Base64.getEncoder().encodeToString(combined);
    }

    public String decrypt(String ciphertext) throws Exception {
        byte[] combined = Base64.getDecoder().decode(ciphertext);
        byte[] iv  = Arrays.copyOfRange(combined, 0, GCM_IV_LEN);
        byte[] enc = Arrays.copyOfRange(combined, GCM_IV_LEN, combined.length);
        javax.crypto.SecretKey key = getEncKey();
        Cipher cipher = Cipher.getInstance(AES_ALGO);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LEN, iv));
        return new String(cipher.doFinal(enc), StandardCharsets.UTF_8);
    }

    private String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // =========================================================================
    // Result DTOs
    // =========================================================================

    public record OAuthResult(boolean success, String message) {
        static OAuthResult ok()           { return new OAuthResult(true,  "Bybit Fast Connect 완료"); }
        static OAuthResult fail(String m) { return new OAuthResult(false, m); }
    }

    public record ConnectResult(boolean success, String message) {
        static ConnectResult ok()           { return new ConnectResult(true,  "Bybit 연결이 완료됐습니다"); }
        static ConnectResult fail(String m) { return new ConnectResult(false, m); }
    }

    public record BalanceResult(
        boolean connected, boolean success, String message,
        List<Map<String, String>> coins,
        LocalDateTime verifiedAt, String authMethod
    ) {
        static BalanceResult notConnected() {
            return new BalanceResult(false, false, "Bybit 거래소가 연결되지 않았습니다",
                List.of(), null, null);
        }
        static BalanceResult error(String m) {
            return new BalanceResult(true, false, m, List.of(), null, null);
        }
        static BalanceResult ok(List<Map<String, String>> coins,
                                LocalDateTime vAt, String method) {
            return new BalanceResult(true, true, "ok", coins, vAt, method);
        }
    }
}
