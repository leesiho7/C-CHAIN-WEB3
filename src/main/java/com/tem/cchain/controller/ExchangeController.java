package com.tem.cchain.controller;

import com.tem.cchain.entity.Member;
import com.tem.cchain.service.PriceService;
import com.tem.cchain.service.WalletService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 거래소 페이지용 API
 * 모든 엔드포인트는 읽기 전용 (온체인 트랜잭션 전송 없음)
 */
@RestController
@RequestMapping("/api/exchange")
@RequiredArgsConstructor
public class ExchangeController {

    private final PriceService priceService;
    private final WalletService walletService;

    /**
     * 포트폴리오 요약 (잔액 × 시세 → 자산 가치)
     * wallet-exchange.html 전용
     */
    @GetMapping("/portfolio")
    public ResponseEntity<Map<String, Object>> getPortfolio(HttpSession session) {
        Member loginMember = (Member) session.getAttribute("loginMember");
        String walletAddress = (loginMember != null) ? loginMember.getWalletaddress() : null;

        Map<String, Map<String, Double>> prices = priceService.getExternalPrices();

        double ethPrice  = getPrice(prices, "ethereum", "usd");
        double ethChange = getPrice(prices, "ethereum", "usd_24h_change");

        double ethBalance = walletService.getEthBalance(walletAddress);
        double omtBalance = walletService.getOmtBalance(walletAddress);

        double ethValueUsd = ethBalance * ethPrice;
        double omtValueUsd = omtBalance * 1.0;   // OMT 고정 $1
        double totalValueUsd = ethValueUsd + omtValueUsd;

        Map<String, Object> eth = new LinkedHashMap<>();
        eth.put("symbol",   "ETH");
        eth.put("balance",  ethBalance);
        eth.put("price",    ethPrice);
        eth.put("change24h", ethChange);
        eth.put("valueUsd", ethValueUsd);

        Map<String, Object> omt = new LinkedHashMap<>();
        omt.put("symbol",   "OMT");
        omt.put("balance",  omtBalance);
        omt.put("price",    1.0);
        omt.put("change24h", 0.0);
        omt.put("valueUsd", omtValueUsd);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("walletAddress",  walletAddress != null ? walletAddress : "");
        response.put("totalValueUsd",  totalValueUsd);
        response.put("assets",         Map.of("ETH", eth, "OMT", omt));

        return ResponseEntity.ok(response);
    }

    /**
     * 시세 비교 (ETH / BTC / SOL 환율)
     */
    @GetMapping("/rates")
    public ResponseEntity<Map<String, Object>> getRates() {
        Map<String, Map<String, Double>> prices = priceService.getExternalPrices();

        Map<String, Object> rates = new LinkedHashMap<>();
        for (String[] pair : new String[][]{
                {"bitcoin",  "BTC"},
                {"ethereum", "ETH"},
                {"solana",   "SOL"}
        }) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("priceUsd",  getPrice(prices, pair[0], "usd"));
            entry.put("change24h", getPrice(prices, pair[0], "usd_24h_change"));
            rates.put(pair[1], entry);
        }

        return ResponseEntity.ok(rates);
    }

    private double getPrice(Map<String, Map<String, Double>> prices, String coin, String field) {
        if (prices == null || !prices.containsKey(coin)) return 0.0;
        Double v = prices.get(coin).get(field);
        return v != null ? v : 0.0;
    }
}
