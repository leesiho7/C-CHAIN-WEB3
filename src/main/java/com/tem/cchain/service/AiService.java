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

    private final RestTemplate restTemplate;

    // OpenAI API 엔드포인트
    private static final String GPT_API_URL = "https://api.openai.com/v1/chat/completions";

    /**
     * [ChatGPT 전용] 번역 검증 및 점수 계산
     * GPT-4o 모델을 사용하여 가장 정확한 분석을 제공합니다.
     */
    public Map<String, Object> verifyTranslation(String originalCn, String userKr) {
        
        if (apiKey == null || apiKey.isEmpty() || "none".equals(apiKey)) {
            log.error("❌ OpenAI API 키가 누락되었거나 'none'입니다.");
            return createErrorMap("API 키를 설정해주세요 (application.properties).");
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
            if (choices == null || choices.isEmpty()) {
                return createErrorMap("GPT 응답에 choices가 없습니다.");
            }
            Map<?, ?> choice = (Map<?, ?>) choices.get(0);
            Map<?, ?> message = (Map<?, ?>) choice.get("message");
            String content = (String) message.get("content");

            // JSON String -> Map 변환
            Map<String, Object> rawResult = new ObjectMapper().readValue(content, Map.class);
            Map<String, Object> finalResult = new HashMap<>();

            // 타입 안정성을 위해 명시적 변환 수행 (타임리프 비교 연산 오류 방지)
            finalResult.put("score", convertToSafeInt(rawResult.get("score"), 0));
            finalResult.put("similarity_with_ai", convertToSafeInt(rawResult.get("similarity_with_ai"), 0));
            finalResult.put("feedback", rawResult.getOrDefault("feedback", "피드백을 생성할 수 없습니다."));

            return finalResult;
        } catch (Exception e) {
            log.error("❌ JSON 파싱 실패: {}", e.getMessage());
            return createErrorMap("데이터 해석 실패: " + e.getMessage());
        }
    }

    private int convertToSafeInt(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        try {
            if (value instanceof Number) {
                return ((Number) value).intValue();
            } else if (value instanceof String) {
                return Integer.parseInt((String) value);
            }
        } catch (Exception e) {
            log.warn("⚠️ 숫자 변환 실패 (value: {}): {}", value, e.getMessage());
        }
        return defaultValue;
    }

    public Map<String, Object> testVerify(String text) {
        return verifyTranslation(text, text);
    }

    private Map<String, Object> createErrorMap(String message) {
        Map<String, Object> errorMap = new HashMap<>();
        errorMap.put("score", 0);
        errorMap.put("similarity_with_ai", 0);
        errorMap.put("feedback", message);
        return errorMap;
    }
}