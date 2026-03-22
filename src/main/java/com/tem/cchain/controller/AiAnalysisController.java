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
            welcome.put("answer", "반갑습니다! 🤖 저는 **Dober AI** 에이전트입니다. 현재 비트코인 차트를 정밀 분석 중입니다. '지금 롱/숏 어때?' 혹은 '파동 분석해봐'라고 자유롭게 물어봐 주세요!");
            welcome.put("status", "STAY");
            return ResponseEntity.ok(welcome);
        }

        // 3. 파이썬 에이전트 호출 준비
        String pythonEndpoint = aiAgentUrl + "/api/v1/analyze-short";
        
        Map<String, Object> pythonRequest = new HashMap<>();
        pythonRequest.put("symbol", symbol);
        pythonRequest.put("current_price", currentPrice);
        pythonRequest.put("df_json", candleData);
        
        // 422 에러 방지를 위한 질문 기본값 설정
        String finalQuestion = (userQuestion == null || userQuestion.trim().isEmpty()) ? "현재 시장 상황을 분석해줘" : userQuestion;
        pythonRequest.put("question", finalQuestion);

        try {
            log.info("🤖 AI 정밀 분석 요청 전송: [{}]", finalQuestion);
            
            // 파이썬 서버로 POST 요청 (Map 형태로 응답 수신)
            Map<String, Object> aiResult = restTemplate.postForObject(pythonEndpoint, pythonRequest, Map.class);

            Map<String, Object> response = new HashMap<>();
            
            // [핵심 보완] 응답 데이터 검증 및 필드 매칭
            if (aiResult != null && aiResult.get("reason") != null) {
                // 파이썬의 'reason'을 프론트가 기다리는 'answer'로 변환하여 전달
                String reasonStr = String.valueOf(aiResult.get("reason"));
                String statusStr = String.valueOf(aiResult.get("decision"));

                response.put("answer", reasonStr.isEmpty() ? "분석 결과가 비어 있습니다." : reasonStr);
                response.put("status", statusStr);
            } else {
                // 통신은 성공했으나 데이터가 유효하지 않을 때
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