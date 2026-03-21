package com.tem.cchain.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier; // 추가

@Slf4j
@RestController
@RequestMapping("/api/ai")
public class AiAnalysisController {

    @Value("${ai.agent.url}")
    private String aiAgentUrl;

    private final RestTemplate restTemplate;

    // @Qualifier를 써서 Web3Config에 있는 restTemplate을 쓰겠다고 명확히 지정합니다.
    public AiAnalysisController(@Qualifier("restTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @PostMapping("/quant-analyze")
    public ResponseEntity<Map<String, Object>> analyze(@RequestBody Map<String, Object> request) {
        // ... (나머지 코드는 이전과 동일합니다) ...
        String symbol = (String) request.getOrDefault("symbol", "BTCUSDT");
        Object btcPriceObj = request.get("btcPrice");
        Double currentPrice = (btcPriceObj instanceof Number) ? ((Number) btcPriceObj).doubleValue() : null;
        String candleData = (String) request.get("candleData");

        String pythonEndpoint = aiAgentUrl + "/api/v1/analyze-short";

        Map<String, Object> pythonRequest = new HashMap<>();
        pythonRequest.put("symbol", symbol);
        pythonRequest.put("current_price", currentPrice);
        pythonRequest.put("df_json", candleData);

        try {
            log.info("🤖 AI 에이전트 분석 요청 시작 [종목: {}]", symbol);
            Map<String, Object> aiResult = restTemplate.postForObject(pythonEndpoint, pythonRequest, Map.class);

            Map<String, Object> response = new HashMap<>();
            if (aiResult != null && "ENTER".equals(aiResult.get("decision"))) {
                response.put("answer", "⚠️ **[Short Specialist 타점 포착]**\n\n" + aiResult.get("reason"));
                response.put("status", "SUCCESS");
            } else {
                response.put("answer", "ℹ️ **[Short Specialist 관망]**\n사유: " + (aiResult != null ? aiResult.get("reason") : "조건 미충족"));
                response.put("status", "STAY");
            }
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("❌ AI 에이전트 통신 에러: {}", e.getMessage());
            Map<String, Object> errorRes = new HashMap<>();
            errorRes.put("answer", "🤖 AI 분석 노드가 과열되었습니다.");
            return ResponseEntity.status(500).body(errorRes);
        }
    }
}