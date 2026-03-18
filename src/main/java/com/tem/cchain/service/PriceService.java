package com.tem.cchain.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.Map;

@Slf4j
@Service
public class PriceService {

    // RestTemplate은 new로 생성하지 않고 Spring이 관리하는 인스턴스 사용
    private final RestTemplate restTemplate;

    public PriceService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    private static final String COINGECKO_URL =
            "https://api.coingecko.com/api/v3/simple/price" +
            "?ids=bitcoin,ethereum,tether&vs_currencies=usd&include_24hr_change=true";

    // ── 수동 TTL 캐시 (CoinGecko 무료: 분당 50회 제한 대응) ──
    private static final long CACHE_TTL_MS = 30_000L; // 30초
    private Map<String, Map<String, Double>> cachedPrices = null;
    private long lastFetchTime = 0L;

    /**
     * 실시간 코인 가격 조회 (30초 캐시 적용)
     * 리턴 형식: { "bitcoin": {"usd": 65000.0, "usd_24h_change": 1.5}, ... }
     * 실패 시 빈 Map 반환 (null 반환 금지 → 호출자 NPE 방지)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Double>> getExternalPrices() {
        long now = System.currentTimeMillis();

        // 캐시 유효 시 즉시 반환
        if (cachedPrices != null && (now - lastFetchTime) < CACHE_TTL_MS) {
            return cachedPrices;
        }

        try {
            log.info("[PriceService] CoinGecko 가격 갱신 요청...");
            Map<String, Map<String, Double>> response =
                    restTemplate.getForObject(COINGECKO_URL, Map.class);

            if (response != null) {
                cachedPrices = response;
                lastFetchTime = now;
                log.info("[PriceService] 가격 수신 완료: BTC=${}, ETH=${}",
                        response.getOrDefault("bitcoin", Collections.emptyMap()).get("usd"),
                        response.getOrDefault("ethereum", Collections.emptyMap()).get("usd"));
            }
        } catch (Exception e) {
            log.error("[PriceService] 가격 조회 실패: {}", e.getMessage());
            // 실패해도 이전 캐시가 있으면 그대로 반환
        }

        return cachedPrices != null ? cachedPrices : Collections.emptyMap();
    }
}
