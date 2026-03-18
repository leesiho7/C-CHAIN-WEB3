package com.tem.cchain.service;

import com.tem.cchain.dto.DashboardSummaryDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final PriceService priceService;
    private final WalletService walletService;

    /**
     * 대시보드 요약 데이터 조합
     * @param walletAddress 세션에서 전달받은 사용자 지갑 주소
     */
    public DashboardSummaryDto getDashboardSummary(String walletAddress) {

        // 1. 외부 가격 조회 (실패 시 빈 Map → NPE 없이 기본값 처리)
        Map<String, Map<String, Double>> prices = priceService.getExternalPrices();
        Map<String, Double> ethData = prices.getOrDefault("ethereum", Collections.emptyMap());

        double ethPrice    = ethData.getOrDefault("usd", 0.0);
        double ethChange   = ethData.getOrDefault("usd_24h_change", 0.0);

        // 2. 온체인 잔액 조회 (walletAddress 파라미터로 전달)
        double ethBalance = walletService.getEthBalance(walletAddress);
        double omtBalance = walletService.getOmtBalance(walletAddress);

        // 3. 총 자산 계산 (OMT는 현재 $1.0 기준, 추후 CoinGecko OMT 시세 연동 가능)
        double totalAssetValue = (ethBalance * ethPrice) + omtBalance;

        log.info("[Dashboard] 총 자산 계산 완료: ${} (지갑: {})", totalAssetValue, walletAddress);

        return DashboardSummaryDto.builder()
                .totalBalanceUsd(totalAssetValue)
                .ethBalance(ethBalance)
                .omtBalance(omtBalance)
                .ethPrice(ethPrice)
                .ethChange24h(ethChange)
                .build();
    }
}
