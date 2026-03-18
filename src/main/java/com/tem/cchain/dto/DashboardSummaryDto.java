package com.tem.cchain.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DashboardSummaryDto {
    private Double totalBalanceUsd; // 총 자산 (예: $45,231.87)
    private Double ethBalance;      // 보유 ETH (예: 12.5)
    private Double omtBalance;      // 보유 OMT (예: 487,650.0)
    private Double ethPrice;        // 현재 ETH 가격
    private Double ethChange24h;    // ETH 24시간 변동률
}