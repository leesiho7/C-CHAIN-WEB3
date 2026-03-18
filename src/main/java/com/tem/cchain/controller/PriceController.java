package com.tem.cchain.controller;

import com.tem.cchain.service.PriceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/price")
@RequiredArgsConstructor
public class PriceController {

    private final PriceService priceService;

    /**
     * 실시간 시세 스트림
     * CoinGecko 응답을 프론트엔드(price-stream.html) 형식으로 변환하여 반환
     * BTC / ETH / SOL + OMT(고정값) 포함
     */
    @GetMapping("/stream")
    public ResponseEntity<Map<String, Object>> getPriceStream() {
        Map<String, Map<String, Double>> raw = priceService.getExternalPrices();

        Map<String, Object> result = new LinkedHashMap<>();

        result.put("BTC", buildEntry(raw, "bitcoin", "BTC", "Bitcoin"));
        result.put("ETH", buildEntry(raw, "ethereum", "ETH", "Ethereum"));
        result.put("SOL", buildEntry(raw, "solana",   "SOL", "Solana"));

        // OMT는 CoinGecko 미등록 → 고정 시세
        Map<String, Object> omt = new LinkedHashMap<>();
        omt.put("symbol",     "OMT");
        omt.put("name",       "OMT Token");
        omt.put("price",      1.0);
        omt.put("change24h",  0.0);
        omt.put("volume24h",  0.0);
        omt.put("marketCap",  0.0);
        result.put("OMT", omt);

        return ResponseEntity.ok(result);
    }

    private Map<String, Object> buildEntry(Map<String, Map<String, Double>> raw,
                                           String coinId, String symbol, String name) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("symbol", symbol);
        entry.put("name",   name);

        if (raw != null && raw.containsKey(coinId)) {
            Map<String, Double> data = raw.get(coinId);
            entry.put("price",     safe(data, "usd"));
            entry.put("change24h", safe(data, "usd_24h_change"));
            entry.put("volume24h", safe(data, "usd_24h_vol"));
            entry.put("marketCap", safe(data, "usd_market_cap"));
        } else {
            entry.put("price",     0.0);
            entry.put("change24h", 0.0);
            entry.put("volume24h", 0.0);
            entry.put("marketCap", 0.0);
        }
        return entry;
    }

    private double safe(Map<String, Double> map, String key) {
        Double v = map.get(key);
        return v != null ? v : 0.0;
    }
}
