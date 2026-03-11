package com.tem.cchain.controller;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.tem.cchain.dto.ActivityDto;
import com.tem.cchain.dto.MyPageStatsDto;
import com.tem.cchain.entity.Member;
import com.tem.cchain.entity.Translation;
import com.tem.cchain.repository.MemberRepository;
import com.tem.cchain.repository.TranslationRepository;
import com.tem.cchain.service.MyPageService;
import com.tem.cchain.service.TokenService;

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Controller
@RequiredArgsConstructor
public class MyPageController {
    
    private final MyPageService myPageService;
    private final TokenService tokenService;
    private final MemberRepository memberRepository;
    private final TranslationRepository translationRepository;

    @GetMapping("/mypage")
    public String myPage(HttpSession session, Model model) {
        // 1. 세션에서 로그인 정보 가져오기
        Member sessionMember = (Member) session.getAttribute("loginMember");
        
        if (sessionMember == null) {
            return "redirect:/login";
        }

        // 최신 DB 정보를 가져옵니다 (ID가 email이므로 getEmail() 사용)
        Member loginMember = memberRepository.findById(sessionMember.getEmail())
                .orElse(sessionMember);
        
        String userAddress = loginMember.getWalletaddress();
        boolean isConnected = (userAddress != null && !userAddress.isEmpty());

        MyPageStatsDto stats = new MyPageStatsDto();
        BigDecimal displayBalance = (loginMember.getOmtBalance() != null) ? loginMember.getOmtBalance() : BigDecimal.ZERO;

        try {
            if (isConnected) {
                // 비동기로 잔액 동기화 요청
                tokenService.syncBalanceAsync(userAddress);
                
                MyPageStatsDto fetchedStats = myPageService.getStats(userAddress);
                if (fetchedStats != null) {
                    stats = fetchedStats;
                }
            }

            // 2. 최근 번역 활동 조회 (DB)
            List<Translation> translations = translationRepository.findByUserOrderByVerifiedAtDesc(loginMember);
            
            // 3. 엔티티 -> ActivityDto 변환 로직
            List<ActivityDto> activityList = new ArrayList<>();
            for (Translation t : translations) {
                ActivityDto adto = new ActivityDto();
                adto.setContentKr(t.getContentKr()); 
                adto.setBlockchainHash(t.getBlockchainHash());
                adto.setRewardAmount("10"); // 기본 보상값 설정
                adto.setVerifiedAt(t.getVerifiedAt());
                activityList.add(adto);
            }
            
            // 4. stats 객체 업데이트
            stats.setRecentActivities(activityList); 
            stats.setTranslatedDocs(activityList.size());
            stats.setVerifiedCases(activityList.size());

        } catch (Exception e) {
            log.error("❌ 마이페이지 로드 중 오류 발생: {}", e.getMessage());
            e.printStackTrace();
        }

        model.addAttribute("isWalletConnected", isConnected);
        model.addAttribute("omtBalance", displayBalance);
        model.addAttribute("stats", stats); 
        model.addAttribute("userAddress", userAddress);

        return "mypage";
    }
}
