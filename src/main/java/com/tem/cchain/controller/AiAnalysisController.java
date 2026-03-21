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

        // 2. [S-Class 로직] 일상적인 인사는 자바에서 가볍게 응답 (필터링 조건 완화)
        // 사용자가 "분석"이나 "타점"을 묻지 않아도 트레이딩 관련 질문은 파이썬으로 보내기 위해
        // 아주 기본적인 인사말만 여기서 처리합니다.
        if (userQuestion != null && userQuestion.matches(".*(안녕|반가|누구|이름|뭐해|하이).*")) {
            Map<String, Object> welcome = new HashMap<>();
            welcome.put("answer", "반갑습니다! 🤖 저는 **Dober AI**입니다. 현재 비트코인 차트를 분석 중입니다. '지금 롱/숏 어때?' 혹은 '파동 분석해줘'라고 자유롭게 물어봐 주세요!");
            welcome.put("status", "STAY");
            return ResponseEntity.ok(welcome);
        }

        // 3. 파이썬 에이전트 호출 준비 (모든 트레이딩 질문은 파이썬 GPT-4가 처리)
        String pythonEndpoint = aiAgentUrl + "/api/v1/analyze-short";
        
        Map<String, Object> pythonRequest = new HashMap<>();
        pythonRequest.put("symbol", symbol);
        pythonRequest.put("current_price", currentPrice);
        pythonRequest.put("df_json", candleData);
        
        // [핵심] 422 에러 방지 및 질문 전달
        // 질문이 비어있으면 기본 분석을 요청하고, 있으면 그 내용을 그대로 파이썬에 전달합니다.
        String finalQuestion = (userQuestion == null || userQuestion.trim().isEmpty()) ? "현재 시장 상황을 분석해줘" : userQuestion;
        pythonRequest.put("question", finalQuestion);

        try {
            log.info("🤖 AI 정밀 분석 요청 전송: [{}]", finalQuestion);
            
            // 파이썬 서버로 POST 요청
            Map<String, Object> aiResult = restTemplate.postForObject(pythonEndpoint, pythonRequest, Map.class);

            Map<String, Object> response = new HashMap<>();
            if (aiResult != null) {
                // 파이썬 GPT가 질문 의도에 맞춰 생성한 'reason'을 그대로 전달
                response.put("answer", aiResult.get("reason"));
                response.put("status", aiResult.get("decision"));
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