package com.tem.cchain.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.util.Map;

@Slf4j
@Service
public class PriceService {
    private final RestTemplate restTemplate = new RestTemplate();
    private static final String COINGECKO_URL = "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin,ethereum,solana,tether&vs_currencies=usd&include_24hr_change=true&include_24hr_vol=true&include_market_cap=true";

    public Map<String, Map<String, Double>> getExternalPrices() {
        try {
            return restTemplate.getForObject(COINGECKO_URL, Map.class);
        } catch (Exception e) {
            log.error("[Price] 시세 조회 에러: {}", e.getMessage());
            return null;
        }
    }
}