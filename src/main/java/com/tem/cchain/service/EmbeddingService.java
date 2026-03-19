package com.tem.cchain.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class EmbeddingService {

    @Value("${openai.api.key}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final String EMBEDDING_URL = "https://api.openai.com/v1/embeddings";

    /**
     * 텍스트를 OpenAI Embedding-3-Small 모델을 통해 1536차원 벡터로 변환합니다.
     */
    public List<Double> getEmbedding(String text) {
        // 1. 헤더 설정 (스프링 프레임워크 전용 임포트 확인)
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        // 2. 바디 설정
        Map<String, Object> body = new HashMap<>();
        body.put("model", "text-embedding-3-small");
        body.put("input", text);

        // 3. 요청 객체 생성 및 전송
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(EMBEDDING_URL, entity, Map.class);

            if (response.getBody() == null) return new ArrayList<>();

            // 4. OpenAI 응답 구조 파싱 (data -> [0] -> embedding)
            List<Map<String, Object>> dataList = (List<Map<String, Object>>) response.getBody().get("data");
            if (dataList != null && !dataList.isEmpty()) {
                return (List<Double>) dataList.get(0).get("embedding");
            }
        } catch (Exception e) {
            System.err.println("Embedding 호출 중 에러 발생: " + e.getMessage());
        }

        return new ArrayList<>();
    }
}