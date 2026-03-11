package com.tem.cchain.service;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.protocol.Web3j;

import com.tem.cchain.entity.Member;
import com.tem.cchain.repository.MemberRepository;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;
    private final Web3j web3j;

    @Value("${ethereum.wallet.private-key}")
    private String adminPrivateKey;

    @Value("${token.contract.address}")
    private String contractAddress;

    public Optional<Member> login(String email, String userpw) {
        return memberRepository.findByEmailAndUserpw(email, userpw);
    }

    @Transactional
    public Member register(Member entity) {
        try {
            ECKeyPair keyPair = Keys.createEcKeyPair();
            String address = "0x" + Keys.getAddress(keyPair);
            String privateKey = keyPair.getPrivateKey().toString(16);
            
            entity.setWalletaddress(address);
            entity.setPrivateKey(privateKey);
            entity.setOmtBalance(java.math.BigDecimal.ZERO);
            
            return memberRepository.save(entity);
        } catch (Exception e) {
            throw new RuntimeException("지갑 생성 중 오류가 발생했습니다: " + e.getMessage());
        }
    }

    public boolean autoDeposit(String email, double amount) throws Exception {
        Optional<Member> memberOpt = memberRepository.findById(email);
        if (memberOpt.isEmpty()) return false;
        
        Member m = memberOpt.get();
        
        // 하드코딩된 키 대신 변수 사용
        Credentials credentials = Credentials.create(adminPrivateKey);
        
        m.setOmtBalance(m.getOmtBalance().add(java.math.BigDecimal.valueOf(amount)));
        return true;
    }

    public boolean checkId(String userid) {
        return memberRepository.existsByUserid(userid);
    }
}
