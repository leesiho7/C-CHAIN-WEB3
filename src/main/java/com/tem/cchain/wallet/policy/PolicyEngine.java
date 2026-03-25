package com.tem.cchain.wallet.policy;

import com.tem.cchain.wallet.policy.dto.PolicyDecision;
import com.tem.cchain.wallet.policy.dto.PolicyRequest;

/**
 * Pac Engine (Policy as Code Engine) 인터페이스.
 *
 * 이 인터페이스를 통해 정책 평가를 추상화합니다.
 * 구현체를 교체하면 OPA ↔ 내부 룰엔진 ↔ 테스트 Mock으로 자유롭게 전환 가능합니다.
 *
 * ---- Policy as Code의 핵심 개념 ----
 * "무엇이 허용되는가"를 코드(정책 파일)로 표현합니다.
 * 비즈니스 로직(Service)과 정책 로직(Policy)이 분리되어:
 * - 정책 변경 시 서비스 코드를 건드리지 않아도 됩니다.
 * - 감사(Audit) 시 정책 파일만 보면 "무엇이 허용됐는지" 알 수 있습니다.
 * - 정책 파일 자체가 버전 관리 대상이 됩니다.
 */
public interface PolicyEngine {

    /**
     * 주어진 요청이 정책을 통과하는지 평가합니다.
     *
     * @param request 평가할 요청 컨텍스트
     * @return PolicyDecision (allow/deny + 이유)
     */
    PolicyDecision evaluate(PolicyRequest request);
}
