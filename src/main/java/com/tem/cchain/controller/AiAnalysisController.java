package com.tem.cchain.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
public class AiAnalysisController {

	@Value("${openai.api.key}")
	private String apiKey;

    @PostMapping("/quant-analyze")
    public ResponseEntity<Map<String, String>> analyze(@RequestBody Map<String, String> request) {
        String userQuestion = request.get("question");
        String currentBtcPrice = request.get("btcPrice");

        // OpenAI API 호출 로직 (RestTemplate 또는 WebClient 사용)
        // 핵심: System Role에 페르소나 주입
        String systemPrompt = "너는 10년 차 베테랑 퀀트 트레이더야. " +
            "사용자가 기술적 분석을 물어보면 반드시 '피보나치 되돌림'과 '선형 회귀 채널' 관점에서 답변해줘. " +
            "현재 비트코인 가격은 $" + currentBtcPrice + "이야. " +
            "전문 용어를 사용하되 친절하게 답변해줘.";

        // ... OpenAI API 호출 코드 (생략) ...
        
        Map<String, String> response = new HashMap<>();
        response.put("answer", "AI가 분석한 답변 내용..."); 
        return ResponseEntity.ok(response);
    }
}