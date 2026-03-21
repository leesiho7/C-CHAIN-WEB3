package com.tem.cchain.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/ai")
public class AiAnalysisController {

    @Value("${ai.agent.url}")
    private String aiAgentUrl;

    private final RestTemplate restTemplate;

    // 생성자 주입 (메모리 누수 방지 및 의존성 주입의 정석)
    public AiAnalysisController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @PostMapping("/quant-analyze")
    public ResponseEntity<Map<String, Object>> analyze(@RequestBody Map<String, Object> request) {
        // 프론트엔드에서 넘어오는 데이터 추출
        String symbol = (String) request.getOrDefault("symbol", "BTCUSDT");
        
        // 데이터 타입 변환 안전장치 (Integer/Long/Float/Double 모두 대응)
        Object btcPriceObj = request.get("btcPrice");
        Double currentPrice = (btcPriceObj instanceof Number) ? ((Number) btcPriceObj).doubleValue() : null;
        
        String candleData = (String) request.get("candleData");

        // 1. 파이썬 에이전트 엔드포인트
        String pythonEndpoint = aiAgentUrl + "/api/v1/analyze-short";

        // 2. 파이썬으로 보낼 데이터 조립
        Map<String, Object> pythonRequest = new HashMap<>();
        pythonRequest.put("symbol", symbol);
        pythonRequest.put("current_price", currentPrice);
        pythonRequest.put("df_json", candleData);

        try {
            log.info("🤖 AI 에이전트 분석 요청 시작 [종목: {}, 현재가: {}]", symbol, currentPrice);
            
            // 3. 파이썬 서버 호출 (POST)
            Map<String, Object> aiResult = restTemplate.postForObject(pythonEndpoint, pythonRequest, Map.class);

            // 4. 응답 구성 (UI 페르소나 적용)
            Map<String, Object> response = new HashMap<>();
            
            if (aiResult != null && "ENTER".equals(aiResult.get("decision"))) {
                String answer = String.format(
                    "⚠️ **[Short Specialist 타점 포착]**\n\n" +
                    "🧐 **분석:** %s\n" +
                    "🎯 **진입가:** $%s\n" +
                    "🛑 **손절가:** $%s\n" +
                    "💰 **익절가:** $%s",
                    aiResult.get("reason"), 
                    aiResult.get("entry_price"), 
                    aiResult.get("stop_loss"), 
                    aiResult.get("take_profit")
                );
                response.put("answer", answer);
                response.put("status", "SUCCESS");
            } else {
                String reason = (aiResult != null) ? (String) aiResult.get("reason") : "조건 미충족";
                response.put("answer", "ℹ️ **[Short Specialist 관망]**\n현재는 진입하기에 적절한 타이밍이 아닙니다.\n사유: " + reason);
                response.put("status", "STAY");
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("❌ AI 에이전트 통신 에러: {}", e.getMessage());
            Map<String, Object> errorRes = new HashMap<>();
            errorRes.put("answer", "🤖 AI 분석 노드가 과열되었습니다. 1분 뒤 다시 시도해 주세요.");
            errorRes.put("status", "ERROR");
            return ResponseEntity.status(500).body(errorRes);
        }
    }
}