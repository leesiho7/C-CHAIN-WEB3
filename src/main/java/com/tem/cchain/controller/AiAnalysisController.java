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
        
        // [1단계] 사용자 질문과 데이터 추출
        String userQuestion = (String) request.get("question"); // 프론트의 "question" 필드
        String symbol = (String) request.getOrDefault("symbol", "BTCUSDT");
        Object btcPriceObj = request.get("btcPrice");
        Double currentPrice = (btcPriceObj instanceof Number) ? ((Number) btcPriceObj).doubleValue() : 0.0;
        String candleData = (String) request.get("candleData");

        // [S-Class 로직 1 & 2] 일상 대화 필터링 및 페르소나 설정
        // 질문에 분석 관련 키워드가 없으면 자바에서 즉시 응답 (서버 자원 절약)
        if (userQuestion != null && userQuestion.matches(".*(안녕|누구|반가|이름|뭐해|하이|기능).*")) {
            Map<String, Object> welcomeRes = new HashMap<>();
            welcomeRes.put("answer", "반갑습니다! 🤖 저는 **Dober AI 에이전트**입니다.\n\n" +
                    "저는 실시간 차트 데이터를 분석하여 **엘리어트 파동 기반의 숏(Short) 타점**을 잡아내는 전문가입니다. " +
                    "\"지금 비트코인 분석해줘\" 또는 \"현재 몇 파동이야?\"라고 물어봐 주세요!");
            welcomeRes.put("status", "STAY");
            return ResponseEntity.ok(welcomeRes);
        }

        // [S-Class 로직 3] 전문 분석 질문은 파이썬 에이전트로 전달
        String pythonEndpoint = aiAgentUrl + "/api/v1/analyze-short";

        Map<String, Object> pythonRequest = new HashMap<>();
        pythonRequest.put("symbol", symbol);
        pythonRequest.put("current_price", currentPrice);
        pythonRequest.put("df_json", candleData);
        pythonRequest.put("question", userQuestion); // 파이썬에게 사용자의 질문도 함께 배달!

        try {
            log.info("🤖 AI 에이전트 정밀 분석 요청 시작: [{}]", userQuestion);
            
            // 파이썬 서버 호출
            Map<String, Object> aiResult = restTemplate.postForObject(pythonEndpoint, pythonRequest, Map.class);

            Map<String, Object> response = new HashMap<>();
            if (aiResult != null) {
                // 파이썬 GPT가 질문에 맞춰 생성한 'reason'을 답변으로 사용
                response.put("answer", aiResult.get("reason"));
                response.put("status", aiResult.get("decision"));
            }
            
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("❌ AI 에이전트 통신 에러: {}", e.getMessage());
            Map<String, Object> errorRes = new HashMap<>();
            errorRes.put("answer", "🤖 AI 분석 노드가 과열되었습니다. 잠시 후 다시 시도해 주세요.");
            errorRes.put("status", "ERROR");
            return ResponseEntity.status(500).body(errorRes);
        }
    }
}