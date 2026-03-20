package com.tem.cchain.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.util.Map;

/**
 * CoinGecko API로 실시간 시세를 조회한다.
 *
 * API 키 사용 방식:
 *   - Demo key (무료): x-cg-demo-api-key 헤더에 설정 → 무료 플랜 rate limit 완화
 *   - Pro key: x-cg-pro-api-key 헤더 사용 (Demo 키로도 동일 헤더 이름 사용)
 *   - COINGECKO_API_KEY 환경변수가 없으면 헤더 없이 요청 (더 엄격한 rate limit 적용)
 */
@Slf4j
@Service
public class PriceService {

    private static final String COINGECKO_URL =
            "https://api.coingecko.com/api/v3/simple/price" +
            "?ids=bitcoin,ethereum,solana,tether" +
            "&vs_currencies=usd" +
            "&include_24hr_change=true" +
            "&include_24hr_vol=true" +
            "&include_market_cap=true";

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${coingecko.api.key:}")
    private String apiKey;

    public Map<String, Map<String, Double>> getExternalPrices() {
        try {
            if (apiKey != null && !apiKey.isBlank()) {
                // API 키가 있으면 헤더에 담아 요청 (rate limit 완화)
                HttpHeaders headers = new HttpHeaders();
                headers.set("x-cg-demo-api-key", apiKey);
                HttpEntity<Void> entity = new HttpEntity<>(headers);
                ResponseEntity<Map> response = restTemplate.exchange(
                        COINGECKO_URL, HttpMethod.GET, entity, Map.class);
                return response.getBody();
            } else {
                // API 키 없음 — 무인증 요청 (rate limit 제한적)
                return restTemplate.getForObject(COINGECKO_URL, Map.class);
            }
        } catch (Exception e) {
            log.error("[Price] 시세 조회 에러: {}", e.getMessage());
            return null;
        }
    }
}