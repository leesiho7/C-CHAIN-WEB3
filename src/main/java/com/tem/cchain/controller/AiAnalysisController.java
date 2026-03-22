package com.tem.cchain.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/ai")
public class AiAnalysisController {

    @Value("${ai.agent.url}")
    private String aiAgentUrl;

    private final RestTemplate restTemplate;

    public AiAnalysisController(@Qualifier("restTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @PostMapping("/quant-analyze")
    public ResponseEntity<Map<String, Object>> analyze(@RequestBody Map<String, Object> request) {
        
        // 1. 프론트엔드로부터 데이터 추출
        String userQuestion = (String) request.get("question");
        String symbol = (String) request.getOrDefault("symbol", "BTCUSDT");
        Object btcPriceObj = request.get("btcPrice");
        Double currentPrice = (btcPriceObj instanceof Number) ? ((Number) btcPriceObj).doubleValue() : 0.0;
        String candleData = (String) request.get("candleData");

        // 2. [페르소나] 일상적인 인사는 자바에서 즉시 응답
        if (userQuestion != null && userQuestion.matches(".*(안녕|반가|누구|이름|뭐해|하이).*")) {
            Map<String, Object> welcome = new HashMap<>();
            welcome.put("answer", "반갑습니다! 🤖 저는 **Dober AI** 에이전트입니다. 현재 비트코인 차트를 정밀 분석 중입니다. '지금 롱/숏 어때?' 혹은 '파동 분석해줘'라고 자유롭게 물어봐 주세요!");
            welcome.put("status", "STAY");
            return ResponseEntity.ok(welcome);
        }

        // 3. 파이썬 에이전트 호출 준비
        String pythonEndpoint = aiAgentUrl + "/api/v1/analyze-short";
        
        Map<String, Object> pythonRequest = new HashMap<>();
        pythonRequest.put("symbol", symbol);
        pythonRequest.put("current_price", currentPrice);
        pythonRequest.put("df_json", candleData);
        
        String finalQuestion = (userQuestion == null || userQuestion.trim().isEmpty()) ? "현재 시장 상황을 분석해줘" : userQuestion;
        pythonRequest.put("question", finalQuestion);

        try {
            log.info("🤖 AI 정밀 분석 요청 전송: [{}]", finalQuestion);
            
            // 파이썬 서버로부터 분석 결과 수신 (visual_data 포함)
            Map<String, Object> aiResult = restTemplate.postForObject(pythonEndpoint, pythonRequest, Map.class);

            Map<String, Object> response = new HashMap<>();
            
            // [핵심 보완] 시각화 데이터(visual_data) 추출 및 전달 로직
            if (aiResult != null && aiResult.get("reason") != null) {
                // 1. 텍스트 분석 결과
                String reasonStr = String.valueOf(aiResult.get("reason"));
                String statusStr = String.valueOf(aiResult.get("decision"));

                response.put("answer", reasonStr.isEmpty() ? "분석 결과가 비어 있습니다." : reasonStr);
                response.put("status", statusStr);

                // 2. [신규] 시각화 좌표 데이터 (파이썬이 준 visual_data가 있으면 그대로 전달)
                if (aiResult.containsKey("visual_data")) {
                    log.info("📊 시각화 좌표 수신 완료: {}", aiResult.get("visual_data"));
                    response.put("visualData", aiResult.get("visual_data"));
                }
                
            } else {
                response.put("answer", "🤖 AI 분석 엔진에서 유효한 리포트를 생성하지 못했습니다. 다시 시도해 주세요.");
                response.put("status", "STAY");
            }
            
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("❌ AI 통신 에러 발생: {}", e.getMessage());
            Map<String, Object> errorRes = new HashMap<>();
            errorRes.put("answer", "🤖 AI 분석 노드에서 응답이 지연되고 있습니다. 잠시 후 다시 시도해 주세요.");
            errorRes.put("status", "ERROR");
            return ResponseEntity.status(500).body(errorRes);
        }
    }
}