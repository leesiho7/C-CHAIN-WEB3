package com.tem.cchain.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tem.cchain.entity.Member;
import com.tem.cchain.entity.Translation;
import com.tem.cchain.repository.MemberRepository;
import com.tem.cchain.repository.TranslationRepository;
import com.tem.cchain.wallet.EnterpriseWalletService;
import com.tem.cchain.wallet.iam.SecurityContextUtil;
import com.tem.cchain.wallet.iam.WalletRole;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 한중 IT 기여 보상 서비스.
 *
 * ---- 하이브리드 흐름 ----
 * 1. 관리자가 번역 검증 승인
 * 2. contributor(유저)의 MetaMask 지갑 주소 조회
 * 3. 서버 KMS 지갑 → 유저 MetaMask 주소로 OMT 보상 전송
 * 4. 유저 기여 횟수 +1, DB 잔액 동기화
 *
 * ---- 보안 구조 ----
 * EnterpriseWalletService.payReward()는 @RequiresWalletRole(REWARD) 보호됨.
 * 서버 내부 호출이므로 SecurityContextUtil.runWith(REWARD)로 임시 권한 부여.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContributionService {

    private final TranslationRepository translationRepository;
    private final MemberRepository memberRepository;
    private final EnterpriseWalletService enterpriseWalletService;

    // 기여 1건당 OMT 보상량 (application.properties에서 설정)
    @Value("${contribution.reward.amount-per-case:10}")
    private BigDecimal rewardAmountPerCase;

    @Transactional
    public void completeVerification(Translation translation) {
        // 1. 중복 승인 방지
        if (translation.getVerifiedAt() != null) return;

        // 2. DB 승인 처리
        translation.setVerifiedAt(LocalDateTime.now());
        translationRepository.save(translation);

        // 3. 기여자 정보 확인
        Member contributor = translation.getUser();
        if (contributor == null) {
            log.warn("[Contribution] 기여자 정보 없음 (translationId={})", translation.getId());
            return;
        }

        contributor.incrementVerifiedCases();
        memberRepository.save(contributor);
        log.info("[Contribution] DB 승인 완료: email={}, 누적기여={}",
            contributor.getEmail(), contributor.getVerifiedCases());

        // 4. MetaMask 지갑 주소 확인
        String walletAddress = contributor.getWalletaddress();
        if (walletAddress == null || walletAddress.isBlank()) {
            log.warn("[Contribution] MetaMask 지갑 미연동 - 보상 지급 보류: email={}",
                contributor.getEmail());
            // 지갑 미연동 유저는 보상 보류 (나중에 연동 후 관리자가 수동 지급)
            return;
        }

        // 5. KMS 지갑 → MetaMask 주소로 OMT 보상 전송
        // SecurityContextUtil: 서버 내부 호출에 REWARD 역할을 임시 부여
        String txHash = sendRewardSafely(contributor.getEmail(), walletAddress);

        // 6. txHash를 Translation에 저장 (AdminController에서 성공 여부 판단에 사용)
        if (txHash != null) {
            translation.setBlockchainHash(txHash);
            translationRepository.save(translation);
        }
    }

    /**
     * KMS 보상 전송 (예외 격리).
     * 블록체인 전송 실패가 DB 트랜잭션을 롤백하지 않도록 별도 처리.
     * 실패 시 감사 로그에 기록되고 관리자가 재처리할 수 있음.
     * @return txHash (성공 시) 또는 null (실패 시)
     */
    private String sendRewardSafely(String email, String walletAddress) {
        try {
            String txHash = SecurityContextUtil.runWith(
                WalletRole.REWARD,
                "system-contribution-reward",   // 감사 로그에 기록될 호출 주체
                () -> enterpriseWalletService.payReward(walletAddress, rewardAmountPerCase)
            );
            log.info("[Contribution] OMT 보상 전송 완료: email={}, address={}, amount={}, txHash={}",
                email, walletAddress, rewardAmountPerCase, txHash);
            return txHash;
        } catch (Exception e) {
            // 블록체인 전송 실패 → DB 롤백 없이 경고 로그만 기록
            // EnterpriseWalletService 내부에서 AuditLog에 FAILURE로 이미 기록됨
            log.error("[Contribution] OMT 보상 전송 실패 (수동 재처리 필요): email={}, address={}, error={}",
                email, walletAddress, e.getMessage());
            return null;
        }
    }
}