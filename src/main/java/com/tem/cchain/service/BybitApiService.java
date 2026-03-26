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
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Bybit V5 REST API 연동 서비스.
 *
 * 보안 설계:
 * - API Secret 은 AES-256-GCM 으로 암호화해서 DB 저장
 * - 암호화 키는 환경변수 EXCHANGE_KEY_ENC_SECRET 에서만 읽음
 * - 요청 서명: HMAC-SHA256(timestamp + apiKey + recvWindow + queryString, apiSecret)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BybitApiService {

    private static final String BYBIT_BASE_URL = "https://api.bybit.com";
    private static final int    RECV_WINDOW    = 5000;
    private static final String AES_ALGO       = "AES/GCM/NoPadding";
    private static final int    GCM_IV_LEN     = 12;
    private static final int    GCM_TAG_LEN    = 128;

    private final ExchangeKeyRepository exchangeKeyRepository;
    private final ObjectMapper          objectMapper = new ObjectMapper();
    private final HttpClient            httpClient   = HttpClient.newHttpClient();

    @Value("${exchange.key.enc.secret:}")
    private String encSecret;

    // =========================================================================
    // Public API — 컨트롤러에서 호출
    // =========================================================================

    /**
     * API Key / Secret 등록 및 Bybit 연결 검증.
     * 검증 성공 시 DB 에 저장(upsert).
     */
    @Transactional
    public ConnectResult connectAndSave(String memberEmail, String apiKey, String apiSecret) {
        // 1. Bybit 서버에서 계정 잔액 조회로 키 유효성 확인
        try {
            JsonNode walletNode = callBybit("/v5/account/wallet-balance",
                "accountType=UNIFIED", apiKey, apiSecret);

            int retCode = walletNode.path("retCode").asInt(-1);
            if (retCode != 0) {
                String msg = walletNode.path("retMsg").asText("API 키 검증 실패");
                return ConnectResult.fail(msg);
            }

            // 2. Secret 암호화
            String encryptedSecret = encrypt(apiSecret);

            // 3. DB upsert
            ExchangeKey key = exchangeKeyRepository
                .findByMemberEmailAndExchange(memberEmail, "BYBIT")
                .orElse(ExchangeKey.builder()
                    .memberEmail(memberEmail)
                    .exchange("BYBIT")
                    .build());

            key.setApiKey(apiKey);
            key.setApiSecretEnc(encryptedSecret);
            key.setStatus("ACTIVE");
            key.setVerifiedAt(LocalDateTime.now());
            exchangeKeyRepository.save(key);

            log.info("[Bybit] API 키 연결 성공: user={}", memberEmail);
            return ConnectResult.ok();

        } catch (Exception e) {
            log.error("[Bybit] 연결 오류: {}", e.getMessage());
            return ConnectResult.fail("Bybit 서버 연결 오류: " + e.getMessage());
        }
    }

    /**
     * 저장된 API 키로 Bybit 잔액 조회.
     */
    public BalanceResult getBalance(String memberEmail) {
        ExchangeKey key = exchangeKeyRepository
            .findByMemberEmailAndExchange(memberEmail, "BYBIT")
            .orElse(null);

        if (key == null || !"ACTIVE".equals(key.getStatus()))
            return BalanceResult.notConnected();

        try {
            String apiSecret = decrypt(key.getApiSecretEnc());
            JsonNode resp = callBybit("/v5/account/wallet-balance",
                "accountType=UNIFIED", key.getApiKey(), apiSecret);

            if (resp.path("retCode").asInt(-1) != 0) {
                String msg = resp.path("retMsg").asText("잔액 조회 실패");
                return BalanceResult.error(msg);
            }

            // 코인별 잔액 파싱
            List<Map<String, String>> coins = new ArrayList<>();
            JsonNode list = resp.path("result").path("list");
            if (list.isArray()) {
                for (JsonNode account : list) {
                    JsonNode coinList = account.path("coin");
                    if (coinList.isArray()) {
                        for (JsonNode coin : coinList) {
                            String coinName    = coin.path("coin").asText();
                            String walletBal   = coin.path("walletBalance").asText("0");
                            String usdValue    = coin.path("usdValue").asText("0");
                            double bal = Double.parseDouble(walletBal);
                            if (bal > 0) {
                                Map<String, String> entry = new LinkedHashMap<>();
                                entry.put("coin",     coinName);
                                entry.put("balance",  walletBal);
                                entry.put("usdValue", usdValue);
                                coins.add(entry);
                            }
                        }
                    }
                }
            }
            return BalanceResult.ok(coins, key.getVerifiedAt());

        } catch (Exception e) {
            log.error("[Bybit] 잔액 조회 오류: {}", e.getMessage());
            return BalanceResult.error("잔액 조회 오류: " + e.getMessage());
        }
    }

    /**
     * 거래소 연결 해제 (DB 삭제).
     */
    @Transactional
    public void disconnect(String memberEmail) {
        exchangeKeyRepository.deleteByMemberEmailAndExchange(memberEmail, "BYBIT");
        log.info("[Bybit] 연결 해제: user={}", memberEmail);
    }

    /**
     * 현재 연결 상태 확인.
     */
    public boolean isConnected(String memberEmail) {
        return exchangeKeyRepository
            .findByMemberEmailAndExchange(memberEmail, "BYBIT")
            .map(k -> "ACTIVE".equals(k.getStatus()))
            .orElse(false);
    }

    // =========================================================================
    // Bybit V5 서명 요청
    // =========================================================================

    private JsonNode callBybit(String path, String queryString,
                                String apiKey, String apiSecret) throws Exception {
        long timestamp = System.currentTimeMillis();
        String paramStr = timestamp + apiKey + RECV_WINDOW + queryString;
        String signature = hmacSha256(paramStr, apiSecret);

        String url = BYBIT_BASE_URL + path + (queryString.isEmpty() ? "" : "?" + queryString);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .header("X-BAPI-API-KEY",    apiKey)
            .header("X-BAPI-TIMESTAMP",  String.valueOf(timestamp))
            .header("X-BAPI-SIGN",       signature)
            .header("X-BAPI-RECV-WINDOW", String.valueOf(RECV_WINDOW))
            .header("Content-Type", "application/json")
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
    // AES-256-GCM 암복호화
    // =========================================================================

    private SecretKey getEncKey() throws Exception {
        if (encSecret == null || encSecret.isBlank())
            throw new IllegalStateException("EXCHANGE_KEY_ENC_SECRET 환경변수가 설정되지 않았습니다");
        byte[] keyBytes = Arrays.copyOf(
            encSecret.getBytes(StandardCharsets.UTF_8), 32); // 256-bit
        return new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * AES-256-GCM 암호화 → Base64(iv + ciphertext)
     */
    public String encrypt(String plaintext) throws Exception {
        SecretKey key  = getEncKey();
        byte[]    iv   = new byte[GCM_IV_LEN];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance(AES_ALGO);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LEN, iv));
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        byte[] combined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
        return Base64.getEncoder().encodeToString(combined);
    }

    /**
     * AES-256-GCM 복호화 ← Base64(iv + ciphertext)
     */
    public String decrypt(String ciphertext) throws Exception {
        byte[]    combined = Base64.getDecoder().decode(ciphertext);
        byte[]    iv       = Arrays.copyOfRange(combined, 0, GCM_IV_LEN);
        byte[]    enc      = Arrays.copyOfRange(combined, GCM_IV_LEN, combined.length);

        SecretKey key    = getEncKey();
        Cipher    cipher = Cipher.getInstance(AES_ALGO);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LEN, iv));
        return new String(cipher.doFinal(enc), StandardCharsets.UTF_8);
    }

    // =========================================================================
    // Result DTOs
    // =========================================================================

    public record ConnectResult(boolean success, String message) {
        static ConnectResult ok()           { return new ConnectResult(true,  "Bybit 연결이 완료됐습니다"); }
        static ConnectResult fail(String m) { return new ConnectResult(false, m); }
    }

    public record BalanceResult(
        boolean connected,
        boolean success,
        String  message,
        List<Map<String, String>> coins,
        LocalDateTime verifiedAt
    ) {
        static BalanceResult notConnected() {
            return new BalanceResult(false, false, "Bybit 거래소가 연결되지 않았습니다", List.of(), null);
        }
        static BalanceResult error(String m) {
            return new BalanceResult(true, false, m, List.of(), null);
        }
        static BalanceResult ok(List<Map<String, String>> coins, LocalDateTime vAt) {
            return new BalanceResult(true, true, "ok", coins, vAt);
        }
    }
}
