package com.tem.cchain.wallet.policy;

import com.tem.cchain.wallet.policy.dto.PolicyDecision;
import com.tem.cchain.wallet.policy.dto.PolicyRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;

/**
 * OPA가 없는 환경(로컬 개발, 테스트)에서 사용하는 내장 룰엔진 구현체.
 *
 * wallet.opa.enabled=false (기본값) 일 때 활성화됩니다.
 *
 * ---- 적용 룰 ----
 * RULE-01: 1회 전송량 최대 한도 초과 거부
 * RULE-02: 일일 누적 전송량 한도 초과 거부
 * RULE-03: 블랙리스트 주소 거부
 * RULE-04: READ_ONLY 역할의 전송 시도 거부
 * RULE-05: 주소 형식 검증 (0x + 40 hex)
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "wallet.opa.enabled", havingValue = "false", matchIfMissing = true)
public class FallbackWalletPolicyEngine implements PolicyEngine {

    // application.properties에서 설정 가능
    @Value("${wallet.policy.max-single-transfer:10000}")
    private BigDecimal maxSingleTransfer;

    @Value("${wallet.policy.max-daily-transfer:100000}")
    private BigDecimal maxDailyTransfer;

    // 블랙리스트 (실제로는 DB나 외부 서비스에서 조회)
    private static final Set<String> BLACKLIST = Set.of(
        "0x0000000000000000000000000000000000000000"  // zero address
    );

    @Override
    public PolicyDecision evaluate(PolicyRequest request) {

        // RULE-04: READ_ONLY 역할은 전송 불가
        if ("READ_ONLY".equals(request.getCallerRole())
                && isTransferOperation(request.getOperationType())) {
            return PolicyDecision.deny("READ_ONLY 역할은 전송 권한이 없습니다", "RULE-04");
        }

        // 잔액 조회는 모든 역할 허용
        if ("BALANCE_CHECK".equals(request.getOperationType())) {
            return PolicyDecision.allow("RULE-BALANCE");
        }

        // RULE-05: 주소 형식 검증
        if (!isValidEthAddress(request.getToAddress())) {
            return PolicyDecision.deny("유효하지 않은 Ethereum 주소: " + request.getToAddress(), "RULE-05");
        }

        // RULE-03: 블랙리스트 검사
        if (BLACKLIST.contains(request.getToAddress().toLowerCase())) {
            return PolicyDecision.deny("블랙리스트 주소로 전송 거부: " + request.getToAddress(), "RULE-03");
        }

        // RULE-01: 1회 전송 한도
        if (request.getAmount() != null && request.getAmount().compareTo(maxSingleTransfer) > 0) {
            return PolicyDecision.deny(
                String.format("1회 전송 한도 초과: %s OMT (최대 %s OMT)", request.getAmount(), maxSingleTransfer),
                "RULE-01"
            );
        }

        // RULE-02: 일일 누적 한도
        BigDecimal projectedDaily = (request.getDailyTotal() == null ? BigDecimal.ZERO : request.getDailyTotal())
            .add(request.getAmount() == null ? BigDecimal.ZERO : request.getAmount());

        if (projectedDaily.compareTo(maxDailyTransfer) > 0) {
            return PolicyDecision.deny(
                String.format("일일 전송 한도 초과: 누적 %s OMT (최대 %s OMT)", projectedDaily, maxDailyTransfer),
                "RULE-02"
            );
        }

        log.debug("[Policy] 허용: role={}, op={}, amount={}",
            request.getCallerRole(), request.getOperationType(), request.getAmount());
        return PolicyDecision.allow("FALLBACK");
    }

    private boolean isValidEthAddress(String address) {
        return address != null && address.matches("^0x[0-9a-fA-F]{40}$");
    }

    private boolean isTransferOperation(String op) {
        return "TRANSFER".equals(op) || "REWARD".equals(op);
    }
}
