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

    @Value("${openai.api.key:none}")
    private String apiKey;

    @PostMapping("/quant-analyze")
    public ResponseEntity<Map<String, String>> analyze(@RequestBody Map<String, String> request) {
        String userQuestion = request.get("question");
        String currentBtcPrice = request.get("btcPrice");

        Map<String, String> response = new HashMap<>();
        response.put("answer", "현재 BTC $" + currentBtcPrice + " 기준: \"" + userQuestion + "\"에 대한 분석 — 피보나치 0.618 되돌림 구간에서 강력한 지지 확인 중.");
        return ResponseEntity.ok(response);
    }
}
