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

    @PostMapping("/api/document/add")
    public ResponseEntity<?> addDocument(@RequestBody Map<String, String> payload, HttpSession session) {
        Member loginMember = (Member) session.getAttribute("loginMember");
        if (loginMember == null) {
            return ResponseEntity.status(401).body(Map.of("message", "로그인이 필요합니다."));
        }

        try {
            Document document = Document.builder()
                    .titleCn(payload.get("titleCn"))
                    .contentCn(payload.get("contentCn"))
                    .sourceName(payload.get("sourceName"))
                    .summary(payload.get("summary"))
                    .status("PENDING")
                    .member(loginMember)
                    .createdAt(java.time.LocalDateTime.now().toString())
                    .build();

            documentRepository.save(document);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("🔥 문서 추가 중 오류 발생: ", e);
            return ResponseEntity.status(500).body(Map.of("message", "서버 오류: " + e.getMessage()));
        }
    }

    @DeleteMapping("/api/document/delete/{id}")
    public ResponseEntity<?> deleteDocument(@PathVariable("id") Long id, HttpSession session) {
        Member loginMember = (Member) session.getAttribute("loginMember");
        
        // 관리자 권한 체크 (admin@cchain.com 계정만 삭제 가능)
        if (loginMember == null || !"admin@cchain.com".equals(loginMember.getEmail())) {
            return ResponseEntity.status(403).body(Map.of("message", "관리자만 삭제할 수 있습니다."));
        }

        try {
            if (!documentRepository.existsById(id)) {
                return ResponseEntity.status(404).body(Map.of("message", "존재하지 않는 문서입니다."));
            }
            
            documentRepository.deleteById(id);
            log.info("🗑️ 문서 삭제 성공: ID {}", id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("🔥 문서 삭제 중 오류 발생: ", e);
            return ResponseEntity.status(500).body(Map.of("message", "서버 오류: " + e.getMessage()));
        }
    }

    /**
     * 완료된 번역본 조회 API
     */
    @GetMapping("/api/translation/completed/{docId}")
    public ResponseEntity<?> getCompletedTranslation(@PathVariable("docId") Long docId) {
        return translationRepository.findFirstByDocumentIdAndVerifiedAtIsNotNullOrderByVerifiedAtDesc(docId)
                .map(t -> ResponseEntity.ok(Map.of(
                    "success", true,
                    "contentKr", t.getContentKr(),
                    "userEmail", t.getUser().getEmail(),
                    "verifiedAt", t.getVerifiedAt().toString()
                )))
                .orElse(ResponseEntity.status(404).body(Map.of("success", false, "message", "완료된 번역본이 없습니다.")));
    }
}