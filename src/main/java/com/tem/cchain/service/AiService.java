package com.tem.cchain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiService {

    @Value("${openai.api.key}")
    private String apiKey;

    // OpenAI API 엔드포인트
    private final String GPT_API_URL = "https://api.openai.com/v1/chat/completions";

    /**
     * [ChatGPT 전용] 번역 검증 및 점수 계산
     * GPT-4o 모델을 사용하여 가장 정확한 분석을 제공합니다.
     */
    public Map<String, Object> verifyTranslation(String originalCn, String userKr) {
        
        if (apiKey == null || apiKey.isEmpty()) {
            log.error("❌ OpenAI API 키가 누락되었습니다.");
            return createErrorMap("API 키를 확인하세요.");
        }

        // 1. 프롬프트 구성 (전문 번역가 페르소나 부여)
        String promptText = String.format(
            "You are a professional translator specializing in Chinese-Korean technical IT documents.\n" +
            "Evaluate the user's translation based on the original Chinese text.\n" +
            "Original: %s\nUser Translation: %s\n" +
            "Return JSON only with keys: 'score' (0-100), 'similarity_with_ai' (0-100), and 'feedback' (in Korean).",
            originalCn, userKr
        );

        try {
            RestTemplate restTemplate = new RestTemplate();
            
            // 2. 헤더 설정
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey); // "Bearer " + apiKey 자동 처리

            // 3. 요청 바디 구성 (JSON 모드 활성화)
            Map<String, Object> requestBody = Map.of(
                "model", "gpt-4o", // 결제하신 계정이면 gpt-4o 사용 가능
                "messages", List.of(
                    Map.of("role", "system", "content", "You are a helpful assistant that outputs JSON."),
                    Map.of("role", "user", "content", promptText)
                ),
                "response_format", Map.of("type", "json_object"), // JSON 출력 강제
                "temperature", 0.7
            );

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            log.info("🚀 ChatGPT (GPT-4o) 분석 시작... 번역 내용 확인 중");
            
            ResponseEntity<Map> response = restTemplate.postForEntity(GPT_API_URL, entity, Map.class);
            
            if (response.getStatusCode() == HttpStatus.OK) {
                log.info("✅ GPT 분석 성공!");
                return parseGptResponse(response.getBody());
            } else {
                return createErrorMap("GPT 응답 에러: " + response.getStatusCode());
            }

        } catch (Exception e) {
            log.error("🔥 ChatGPT 연동 중 심각한 에러: {}", e.getMessage());
            return createErrorMap("연동 실패: " + e.getMessage());
        }
    }

    /**
     * GPT 응답 데이터에서 필요한 JSON 부분만 추출
     */
    private Map<String, Object> parseGptResponse(Map<String, Object> body) {
        try {
            List<?> choices = (List<?>) body.get("choices");
            Map<?, ?> choice = (Map<?, ?>) choices.get(0);
            Map<?, ?> message = (Map<?, ?>) choice.get("message");
            String content = (String) message.get("content");

            return new ObjectMapper().readValue(content, Map.class);
        } catch (Exception e) {
            log.error("❌ JSON 파싱 실패: {}", e.getMessage());
            return createErrorMap("데이터 해석 실패");
        }
    }

    public Map<String, Object> testVerify(String text) {
        return verifyTranslation(text, text);
    }

    private Map<String, Object> createErrorMap(String message) {
        return Map.of(
            "score", 0, 
            "similarity_with_ai", 0, 
            "feedback", message
        );
    }
}