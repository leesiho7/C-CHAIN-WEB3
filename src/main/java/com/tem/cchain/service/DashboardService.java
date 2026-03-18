package com.tem.cchain.service;

import com.tem.cchain.dto.DashboardSummaryDto;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final PriceService priceService;
    
    @Lazy // WalletService <-> SyncService 간의 순환 참조 방지
    private final WalletService walletService;

    public DashboardSummaryDto getDashboardSummary(String walletAddress) {
        Map<String, Map<String, Double>> prices = priceService.getExternalPrices();
        
        double ethBalance = walletService.getEthBalance(walletAddress);
        double omtBalance = walletService.getOmtBalance(walletAddress);

        // 가격 정보가 없을 경우를 대비한 방어 로직
        double ethPrice = (prices != null && prices.containsKey("ethereum")) ? prices.get("ethereum").get("usd") : 0.0;
        double ethChange = (prices != null && prices.containsKey("ethereum")) ? prices.get("ethereum").get("usd_24h_change") : 0.0;
        
        // 총 자산 가치 계산 (OMT 가치는 임의로 $1.0 설정 혹은 0 처리)
        double totalValue = (ethBalance * ethPrice) + (omtBalance * 1.0);

        return DashboardSummaryDto.builder()
                .totalBalanceUsd(totalValue)
                .ethBalance(ethBalance)
                .omtBalance(omtBalance)
                .ethPrice(ethPrice)
                .ethChange24h(ethChange)
                .build();
    }
}