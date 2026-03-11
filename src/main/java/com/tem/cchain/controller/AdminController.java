package com.tem.cchain.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.transaction.annotation.Transactional;

import com.tem.cchain.entity.Member;
import com.tem.cchain.entity.Translation;
import com.tem.cchain.entity.Document;
import com.tem.cchain.repository.TranslationRepository;
import com.tem.cchain.repository.DocumentRepository;
import com.tem.cchain.service.ContributionService;
import com.tem.cchain.service.TokenService;
import com.tem.cchain.service.AiService; // ChatGPT 전용으로 업데이트된 서비스

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.List;
import java.util.Map;

/**
 * C-Chain Admin Console Controller
 * ChatGPT GPT-4o 분석과 Sepolia 블록체인 보상 시스템 연동
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class AdminController {

    private final TranslationRepository translationRepository;
    private final DocumentRepository documentRepository;
    private final ContributionService contributionService;
    private final TokenService tokenService;
    private final AiService aiService;

    /**
     * 1. 관리자 메인 페이지 (검증 대기 리스트)
     */
    @GetMapping("/admin")
    public String adminPage(HttpSession session, Model model) {
        Member loginMember = (Member) session.getAttribute("loginMember"); 
        
        // 관리자 권한 체크 (이메일 기반)
        if (loginMember == null || !"admin@cchain.com".equals(loginMember.getEmail())) {
            log.warn("🚨 비인가 사용자의 관리자 페이지 접근 시도: {}", 
                     (loginMember != null ? loginMember.getEmail() : "Guest"));
            return "redirect:/login"; 
        }
        
        List<Translation> pendingList = translationRepository.findByVerifiedAtIsNull();
        model.addAttribute("pendingList", pendingList);
        return "admin"; 
    }

    /**
     * 2. 상세 검수 페이지 (ChatGPT GPT-4o 실시간 채점 연동)
     */
    @GetMapping("/admin/detail/{id}")
    @Transactional(readOnly = true)
    public String viewDetail(@PathVariable("id") Long id, HttpSession session, Model model) {
        Member loginMember = (Member) session.getAttribute("loginMember");
        if (loginMember == null || !"admin@cchain.com".equals(loginMember.getEmail())) {
            return "redirect:/login";
        }

        // Translation 데이터 및 연관 정보 조회
        Translation translation = translationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("내역을 찾을 수 없습니다."));
        
        Document document = translation.getDocument();
        Member user = translation.getUser();

        if (document == null) {
            document = Document.builder()
                    .titleCn("원본 문서 없음")
                    .contentCn("내용을 불러올 수 없습니다.")
                    .build();
        }

        // 🤖 [AI 핵심 로직] ChatGPT GPT-4o 모델을 호출하여 번역 품질 정밀 분석
        log.info("🚀 ChatGPT (GPT-4o) 분석 요청 시작 (ID: {})", id);
        
        // AiService에서 새로 만든 verifyTranslation 호출 (모델 구분 없이 단일 호출로 수정됨)
        Map<String, Object> aiResult = aiService.verifyTranslation(
            document.getContentCn(), 
            translation.getContentKr()
        );
        
        log.info("🚀 ChatGPT 분석 결과 수신: {}", aiResult);

        model.addAttribute("translation", translation);
        model.addAttribute("document", document); 
        model.addAttribute("user", user);
        model.addAttribute("aiResult", aiResult); // 프론트엔드에서 ${aiResult.score}, ${aiResult.feedback} 사용

        return "admin_detail"; 
    }

    /**
     * 3. [승인] 처리 (Sepolia 블록체인 100 OMT 지급)
     */
    @PostMapping("/admin/approve/{id}")
    @Transactional
    public String approve(@PathVariable("id") Long id, RedirectAttributes rttr) {
        try {
            Translation tr = translationRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("내역을 찾을 수 없습니다."));
            
            Member contributor = tr.getUser();

            if (contributor != null && contributor.getWalletaddress() != null) {
                // 토큰 지급 로직 실행 (Web3j 연동)
                log.info("💰 토큰 지급 시작: {}", contributor.getEmail());
                String realTxHash = tokenService.rewardContribution(contributor.getEmail(), 100L);
                
                if (realTxHash != null) {
                    tr.setBlockchainHash(realTxHash);
                    contributionService.completeVerification(tr);
                    
                    Document doc = tr.getDocument();
                    if (doc != null) { 
                        doc.setStatus("COMPLETED"); 
                        documentRepository.save(doc); 
                    }
                    rttr.addFlashAttribute("message", "✅ 승인 및 보상 지급 완료! (TX: " + realTxHash + ")");
                } else {
                    rttr.addFlashAttribute("error", "❌ 블록체인 전송 실패. 테스트넷 상태를 확인하세요.");
                }
            } else {
                rttr.addFlashAttribute("error", "❌ 기여자의 지갑 주소가 등록되어 있지 않습니다.");
            }
        } catch (Exception e) {
            log.error("🔥 승인 처리 오류: ", e);
            rttr.addFlashAttribute("error", "시스템 오류: " + e.getMessage());
        }
        return "redirect:/admin";
    }

    /**
     * 4. [반려] 처리
     */
    @PostMapping("/admin/reject/{id}")
    @Transactional
    public String reject(@PathVariable("id") Long id, @RequestParam("feedback") String feedback, RedirectAttributes rttr) {
        try {
            Translation tr = translationRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("내역을 찾을 수 없습니다."));
            
            // 검증 완료 상태로 변경 (보상은 지급하지 않음)
            contributionService.completeVerification(tr); 
            rttr.addFlashAttribute("message", "⚠️ 반려 처리가 완료되었습니다.");
        } catch (Exception e) {
            log.error("🔥 반려 처리 오류: ", e);
            rttr.addFlashAttribute("error", "반려 처리 중 오류 발생");
        }
        return "redirect:/admin";
    }
}