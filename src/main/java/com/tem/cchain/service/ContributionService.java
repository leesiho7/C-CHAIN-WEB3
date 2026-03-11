package com.tem.cchain.service;

import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.tem.cchain.entity.Member;
import com.tem.cchain.entity.Translation;
import com.tem.cchain.repository.MemberRepository;
import com.tem.cchain.repository.TranslationRepository;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class ContributionService {
	
    private final TranslationRepository translationRepository;
    private final MemberRepository memberRepository;

    @Autowired
    private TokenService tokenService; 

    public ContributionService(TranslationRepository translationRepository, MemberRepository memberRepository) {
        this.translationRepository = translationRepository;
        this.memberRepository = memberRepository;
    }

 // ContributionService.java 수정
    @Transactional
    public void completeVerification(Translation translation) {
        // 1. 이미 승인된 건인지 체크
        if (translation.getVerifiedAt() != null) return;

        // 2. 승인 날짜 및 DB 업데이트
        translation.setVerifiedAt(LocalDateTime.now()); 
        
        // 3. 사용자 기여 횟수 증가
        Member contributor = translation.getUser();
        if (contributor != null) {
            contributor.incrementVerifiedCases(); 
            memberRepository.save(contributor);
        }

        translationRepository.save(translation);
        log.info("✅ DB 최종 승인 완료 (ID: {})", translation.getId());
    }
}