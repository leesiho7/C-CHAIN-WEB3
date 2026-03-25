package com.tem.cchain.wallet.policy.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * OPA 정책 평가 결과.
 *
 * allow=true  → 정책이 허용
 * allow=false → 정책이 거부 (reason에 이유 포함)
 */
@Getter
@Builder
public class PolicyDecision {

    private final boolean allow;
    private final String reason;
    private final String policyId;  // 어떤 룰이 결정했는지 추적

    public static PolicyDecision allow(String policyId) {
        return PolicyDecision.builder()
            .allow(true)
            .reason("허용")
            .policyId(policyId)
            .build();
    }

    public static PolicyDecision deny(String reason, String policyId) {
        return PolicyDecision.builder()
            .allow(false)
            .reason(reason)
            .policyId(policyId)
            .build();
    }
}
