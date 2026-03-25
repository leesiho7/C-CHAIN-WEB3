package com.tem.cchain.wallet.policy.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * OPA에게 평가를 요청할 때 전달하는 입력 데이터.
 *
 * OPA 정책(.rego)의 input 객체에 대응됩니다:
 *   input.caller_role     → 요청자의 역할 (MASTER, REWARD, READ_ONLY)
 *   input.to_address      → 수신 지갑 주소
 *   input.amount          → 전송량 (OMT 단위)
 *   input.token_type      → "OMT" | "ETH"
 *   input.daily_total     → 오늘 누적 전송량 (OMT 단위)
 *   input.caller_ip       → 요청자 IP (이상 징후 감지용)
 */
@Getter
@Builder
public class PolicyRequest {
    private final String callerRole;
    private final String toAddress;
    private final BigDecimal amount;
    private final String tokenType;
    private final BigDecimal dailyTotal;
    private final String callerIp;
    private final String operationType;  // "TRANSFER", "REWARD", "BALANCE_CHECK"
}
