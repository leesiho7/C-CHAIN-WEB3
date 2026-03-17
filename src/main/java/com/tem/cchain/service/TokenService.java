package com.tem.cchain.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.concurrent.TimeUnit;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Convert;

import com.tem.cchain.contract.MyToken;
import com.tem.cchain.entity.Member;
import com.tem.cchain.repository.MemberRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class TokenService {

    private final ObjectProvider<Web3j> web3jProvider;
    private final ObjectProvider<MyToken> myTokenProvider;
    private final MemberRepository memberRepository;
    private final ObjectProvider<RedissonClient> redissonClientProvider;

    @Value("${ethereum.admin.address}")
    private String adminWalletAddress;

    /**
     * [기능 1] 비동기 잔액 동기화
     */
    @Async
    public void syncBalanceAsync(String walletAddress) {
        if (walletAddress == null || walletAddress.isEmpty()) return;
        
        MyToken myToken = myTokenProvider.getIfAvailable();
        if (myToken == null) return;
        
        try {
            // 노드가 블록을 확정할 시간을 위해 아주 잠깐 대기 (선택 사항)
            // Thread.sleep(2000); 

            BigInteger balanceWei = myToken.balanceOf(walletAddress).send();
            
            // Web3j 공용 유틸리티 사용 (Wei -> Ether 변환)
            BigDecimal balanceEth = Convert.fromWei(new BigDecimal(balanceWei), Convert.Unit.ETHER)
                                           .setScale(2, BigDecimal.ROUND_HALF_UP);

            Member member = memberRepository.findByWalletaddressIgnoreCase(walletAddress);
            if (member != null) {
                member.setOmtBalance(balanceEth);
                memberRepository.save(member);
                log.info("🔄 [잔액동기화] {} -> {} OMT", walletAddress, balanceEth);
            }
        } catch (Exception e) {
            log.error("❌ 잔액 동기화 실패 ({}): {}", walletAddress, e.getMessage());
        }
    }

    /**
     * [기능 2] 마스터 지갑에서 전송
     */
    public String transferFromMaster(String toAddress, long amount) throws Exception {
        MyToken myToken = myTokenProvider.getIfAvailable();
        if (myToken == null) throw new RuntimeException("블록체인 설정 오류");

        // Ether -> Wei 변환 (10^18 곱하기 자동처리)
        BigInteger weiAmount = Convert.toWei(BigDecimal.valueOf(amount), Convert.Unit.ETHER).toBigInteger();
        
        // 전송 및 영수증 대기
        TransactionReceipt receipt = myToken.transfer(toAddress, weiAmount).send();
        String txHash = receipt.getTransactionHash();
        
        log.info("💸 전송 완료! Hash: {}", txHash);

        // 영수증 확인 후 동기화 호출
        this.syncBalanceAsync(toAddress);
        this.syncBalanceAsync(adminWalletAddress);
        
        return txHash;
    }

    /**
     * [기능 3] 기여 보상 시스템 (분산 락 적용)
     */
    public String rewardContribution(String userEmail, long rewardAmount) {
        RedissonClient redissonClient = redissonClientProvider.getIfAvailable();

        if (redissonClient == null) {
            return executeRewardInternal(userEmail, rewardAmount);
        }

        String lockKey = "reward_lock:" + userEmail;
        RLock lock = redissonClient.getLock(lockKey);
        
        try {
            if (lock.tryLock(10, 30, TimeUnit.SECONDS)) { // 대기시간/락유지시간 현실화
                return executeRewardInternal(userEmail, rewardAmount);
            }
            log.warn("⚠️ 보상 중복 요청 차단: {}", userEmail);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    private String executeRewardInternal(String userEmail, long rewardAmount) {
        try {
            MyToken myToken = myTokenProvider.getIfAvailable();
            if (myToken == null) return null;

            Member member = memberRepository.findById(userEmail)
                    .orElseThrow(() -> new RuntimeException("회원 없음"));
            
            String walletAddress = member.getWalletaddress();
            BigInteger weiAmount = Convert.toWei(BigDecimal.valueOf(rewardAmount), Convert.Unit.ETHER).toBigInteger();
            
            // 전송 실행
            TransactionReceipt receipt = myToken.transfer(walletAddress, weiAmount).send();
            
            if (!receipt.isStatusOK()) {
                log.error("❌ 블록체인 트랜잭션 실패: {}", receipt.getTransactionHash());
                return null;
            }

            this.syncBalanceAsync(walletAddress);
            this.syncBalanceAsync(adminWalletAddress);

            return receipt.getTransactionHash();
        } catch (Exception e) {
            log.error("❌ 보상 지급 에러: {}", e.getMessage());
            return null;
        }
    }
}