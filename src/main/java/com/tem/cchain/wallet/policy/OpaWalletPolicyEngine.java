package com.tem.cchain.wallet.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tem.cchain.wallet.policy.dto.PolicyDecision;
import com.tem.cchain.wallet.policy.dto.PolicyRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * OPA(Open Policy Agent) HTTP API를 통해 정책을 평가하는 구현체.
 *
 * ---- OPA 실행 방법 (Docker) ----
 * docker run -d --name opa -p 8181:8181 \
 *   -v $(pwd)/src/main/resources/opa:/policies \
 *   openpolicyagent/opa:latest run --server /policies
 *
 * ---- OPA 정책 로드 확인 ----
 * curl http://localhost:8181/v1/policies
 *
 * ---- 평가 API 형식 ----
 * POST http://localhost:8181/v1/data/wallet/allow
 * Body: { "input": { "caller_role": "MASTER", "amount": 1000, ... } }
 * Response: { "result": true } or { "result": false }
 *
 * wallet.opa.enabled=true 일 때만 활성화 (false면 FallbackPolicyEngine 사용)
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "wallet.opa.enabled", havingValue = "true")
public class OpaWalletPolicyEngine implements PolicyEngine {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${wallet.opa.url:http://localhost:8181}")
    private String opaBaseUrl;

    @Value("${wallet.opa.policy-path:wallet/allow}")
    private String policyPath;

    /**
     * OPA에 HTTP 요청을 보내 정책 결과를 받습니다.
     *
     * 요청 구조:
     * {
     *   "input": {
     *     "caller_role": "MASTER",
     *     "to_address": "0xabc...",
     *     "amount": 500.0,
     *     "token_type": "OMT",
     *     "daily_total": 1200.0,
     *     "caller_ip": "192.168.1.1",
     *     "operation_type": "TRANSFER"
     *   }
     * }
     */
    @Override
    public PolicyDecision evaluate(PolicyRequest request) {
        try {
            // input 맵 구성 (rego 파일의 input.xxx 와 이름 맞춤)
            Map<String, Object> input = new HashMap<>();
            input.put("caller_role", request.getCallerRole());
            input.put("to_address", request.getToAddress());
            input.put("amount", request.getAmount());
            input.put("token_type", request.getTokenType());
            input.put("daily_total", request.getDailyTotal());
            input.put("caller_ip", request.getCallerIp());
            input.put("operation_type", request.getOperationType());

            Map<String, Object> body = Map.of("input", input);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            String url = opaBaseUrl + "/v1/data/" + policyPath;
            String responseJson = restTemplate.postForObject(
                url,
                new HttpEntity<>(body, headers),
                String.class
            );

            JsonNode root = objectMapper.readTree(responseJson);
            JsonNode result = root.path("result");

            // ---- OPA 응답 형식 ----
            // 단순 allow: { "result": true }
            // 상세 응답:   { "result": { "allow": true, "deny_reason": "..." } }
            if (result.isBoolean()) {
                boolean allow = result.asBoolean();
                return allow
                    ? PolicyDecision.allow(policyPath)
                    : PolicyDecision.deny("OPA 정책 거부", policyPath);
            }

            if (result.isObject()) {
                boolean allow = result.path("allow").asBoolean(false);
                String reason = result.path("deny_reason").asText("OPA 정책 거부");
                return allow
                    ? PolicyDecision.allow(policyPath)
                    : PolicyDecision.deny(reason, policyPath);
            }

            // result가 없으면 정의되지 않은 정책 → 기본 거부 (Fail-Close 원칙)
            log.warn("[OPA] 정책 결과 없음, Fail-Close 적용: {}", responseJson);
            return PolicyDecision.deny("정책 미정의 (Fail-Close)", policyPath);

        } catch (Exception e) {
            // OPA 서버 장애 시에도 Fail-Close: 거부
            log.error("[OPA] 정책 평가 실패 - 거부로 처리: {}", e.getMessage());
            return PolicyDecision.deny("OPA 서버 연결 실패 (Fail-Close)", policyPath);
        }
    }
}
