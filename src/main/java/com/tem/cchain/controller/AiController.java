package com.tem.cchain.controller;

import com.tem.cchain.service.AiService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiService aiService;

    @GetMapping("/test")
    public Map<String, Object> aiTest(@RequestParam String msg) {
        // 서비스의 재정의된 메서드 호출 (빨간 줄 해결!)
        return aiService.testVerify(msg);
    }
}