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

    @Value("${openai.api.key:none}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private static final String EMBEDDING_URL = "https://api.openai.com/v1/embeddings";

    public List<Double> getEmbedding(String text) {
        if ("none".equals(apiKey)) return new ArrayList<>();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        Map<String, Object> body = new HashMap<>();
        body.put("model", "text-embedding-3-small");
        body.put("input", text);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                EMBEDDING_URL, new HttpEntity<>(body, headers), Map.class);
            if (response.getBody() == null) return new ArrayList<>();
            List<Map<String, Object>> dataList = (List<Map<String, Object>>) response.getBody().get("data");
            if (dataList != null && !dataList.isEmpty()) {
                return (List<Double>) dataList.get(0).get("embedding");
            }
        } catch (Exception e) {
            System.err.println("Embedding 호출 에러: " + e.getMessage());
        }
        return new ArrayList<>();
    }
}
