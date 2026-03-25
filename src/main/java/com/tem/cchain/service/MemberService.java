package com.tem.cchain.service;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.tem.cchain.entity.Member;
import com.tem.cchain.repository.MemberRepository;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 회원 서비스.
 *
 * ---- 하이브리드 지갑 구조 ----
 * 유저  : MetaMask 지갑 → 개인키는 MetaMask가 관리, 서버는 절대 보관하지 않음
 * 서버  : AWS KMS 지갑 → 보상(OMT) 전송 전용
 *
 * 기존에 서버에서 ECKeyPair를 생성해 privateKey를 DB에 저장하던 방식을 제거.
 * 유저는 회원가입 후 MetaMask 연동 화면에서 직접 지갑 주소를 연결함.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;

    public Optional<Member> login(String email, String userpw) {
        return memberRepository.findByEmailAndUserpw(email, userpw);
    }

    /**
     * 회원가입.
     * 지갑 주소는 이 시점에 비워두고, MetaMask 연동 후 connectWallet()으로 저장.
     */
    @Transactional
    public Member register(Member entity) {
        entity.setOmtBalance(java.math.BigDecimal.ZERO);
        return memberRepository.save(entity);
    }

    /**
     * MetaMask 지갑 주소 연동.
     * 프론트엔드에서 MetaMask 서명 검증 후 이 메서드로 주소를 저장함.
     *
     * @param email         회원 이메일
     * @param walletAddress MetaMask 주소 (0x...)
     */
    @Transactional
    public void connectWallet(String email, String walletAddress) {
        Member member = memberRepository.findById(email)
            .orElseThrow(() -> new RuntimeException("회원을 찾을 수 없습니다: " + email));

        if (!walletAddress.matches("^0x[0-9a-fA-F]{40}$")) {
            throw new IllegalArgumentException("유효하지 않은 Ethereum 주소: " + walletAddress);
        }

        member.setWalletaddress(walletAddress);
        log.info("[MetaMask] 지갑 연동: email={}, address={}", email, walletAddress);
    }

    @Transactional
    public boolean autoDeposit(String email, double amount) {
        Optional<Member> memberOpt = memberRepository.findById(email);
        if (memberOpt.isEmpty()) return false;

        Member m = memberOpt.get();
        m.setOmtBalance(m.getOmtBalance().add(java.math.BigDecimal.valueOf(amount)));
        memberRepository.save(m);
        return true;
    }

    public boolean checkId(String userid) {
        return memberRepository.existsByUserid(userid);
    }
}
