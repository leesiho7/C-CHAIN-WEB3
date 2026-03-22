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
        
        // 1. 프론트엔드로부터 실시간 데이터 추출 (실시간 시세 유지)
        String userQuestion = (String) request.get("question");
        String symbol = (String) request.getOrDefault("symbol", "BTCUSDT");
        Object btcPriceObj = request.get("btcPrice");
        Double currentPrice = (btcPriceObj instanceof Number) ? ((Number) btcPriceObj).doubleValue() : 0.0;
        String candleData = (String) request.get("candleData");

        // 2. 일상 대화 즉시 응답 (페르소나)
        if (userQuestion != null && userQuestion.matches(".*(안녕|반가|누구|이름|뭐해|하이).*")) {
            Map<String, Object> welcome = new HashMap<>();
            welcome.put("answer", "반갑습니다! 유저님. 🤖 저는 **Dober AI**입니다. 현재 차트를 분석 중이니 질문해 주세요!");
            welcome.put("status", "STAY");
            return ResponseEntity.ok(welcome);
        }

        // 3. 파이썬 에이전트 호출 설정
        String pythonEndpoint = aiAgentUrl + "/api/v1/analyze-short";
        Map<String, Object> pythonRequest = new HashMap<>();
        pythonRequest.put("symbol", symbol);
        pythonRequest.put("current_price", currentPrice); // 실시간 가격 전송
        pythonRequest.put("df_json", candleData);
        pythonRequest.put("question", (userQuestion == null || userQuestion.trim().isEmpty()) ? "현재 시장 상황을 분석해줘" : userQuestion);

        try {
            log.info("🤖 AI 정밀 분석 요청 전송 (Price: {}): [{}]", currentPrice, pythonRequest.get("question"));
            
            // 파이썬 서버로부터 분석 결과 수신
            Map<String, Object> aiResult = restTemplate.postForObject(pythonEndpoint, pythonRequest, Map.class);

            Map<String, Object> response = new HashMap<>();
            
            if (aiResult != null) {
                // 파이썬 응답 필드와 프론트엔드 기대 필드 매칭
                String reason = aiResult.get("reason") != null ? String.valueOf(aiResult.get("reason")) : "분석 리포트를 생성할 수 없습니다.";
                String decision = aiResult.get("decision") != null ? String.valueOf(aiResult.get("decision")) : "STAY";

                response.put("answer", reason);
                response.put("status", decision);

                // [핵심] 시각화 좌표(visual_data)가 있다면 visualData 키로 프론트에 전달
                if (aiResult.containsKey("visual_data")) {
                    log.info("📊 시각화 좌표 수집 완료 -> 프론트엔드 전송");
                    response.put("visualData", aiResult.get("visual_data"));
                }
            } else {
                response.put("answer", "🤖 AI 분석 엔진 응답이 유효하지 않습니다.");
                response.put("status", "STAY");
            }
            
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("❌ AI 통신 에러: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of(
                "answer", "🤖 AI 분석 노드 응답 지연 중입니다.",
                "status", "ERROR"
            ));
        }
    }
}