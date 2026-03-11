package com.tem.cchain.controller;

import java.util.HashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.tem.cchain.dto.TranslationDto;
import com.tem.cchain.entity.Member;
import com.tem.cchain.entity.Translation;
import com.tem.cchain.entity.Document;
import com.tem.cchain.repository.TranslationRepository;
import com.tem.cchain.repository.DocumentRepository;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequiredArgsConstructor
@Slf4j
public class TranslationApiController {

    private final TranslationRepository translationRepository;
    private final DocumentRepository documentRepository;

    @PostMapping("/api/translation/submit")
    public ResponseEntity<?> submit(@RequestBody TranslationDto dto, HttpSession session) {
        
        Member loginMember = (Member) session.getAttribute("loginMember");
        if (loginMember == null) {
            return ResponseEntity.status(401).body(Map.of("message", "로그인이 필요합니다."));
        }

        try {
            // 1. DTO에서 넘어온 String 타입 ID를 Long으로 변환
            Long docIdLong = Long.parseLong(dto.getDocumentId());
            
            // 2. 원본 문서 조회 (findById 사용)
            Document doc = documentRepository.findById(docIdLong)
                    .orElseThrow(() -> new IllegalArgumentException("원본 문서(ID: " + docIdLong + ")를 찾을 수 없습니다."));

            // 3. Translation 객체 생성 및 연관 관계 설정
            Translation translation = new Translation();
            translation.setContentKr(dto.getContentKr()); 
            translation.setBlockchainHash(dto.getBlockchainHash());
            translation.setUser(loginMember);
            translation.setDocument(doc); // 👈 핵심: 여기서 doc_id가 연결됩니다.

            // 4. DB 저장
            translationRepository.save(translation);
            log.info("✅ 번역 저장 성공: 문서 ID {}", docIdLong);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "성공적으로 제출되었습니다!");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("🔥 저장 중 오류 발생: ", e);
            return ResponseEntity.status(500).body(Map.of("success", false, "message", "서버 오류: " + e.getMessage()));
        }
    }
}