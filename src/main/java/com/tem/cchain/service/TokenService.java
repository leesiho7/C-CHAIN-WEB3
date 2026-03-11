package com.tem.cchain.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.concurrent.TimeUnit;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;

import com.tem.cchain.contract.MyToken;
import com.tem.cchain.entity.Member;
import com.tem.cchain.repository.MemberRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class TokenService {

    private final Web3j web3j;
    private final MyToken myToken;
    private final MemberRepository memberRepository;
    
    // ObjectProvider를 사용하여 빈이 없어도 에러가 나지 않게 합니다.
    private final ObjectProvider<RedissonClient> redissonClientProvider;

    @Value("${ethereum.wallet.private-key}")
    private String masterPrivateKey;

    @Value("${ethereum.admin.address}")
    private String adminWalletAddress;

    /**
     * [기능 1] 비동기 잔액 동기화
     */
    @Async
    public void syncBalanceAsync(String walletAddress) {
        if (walletAddress == null || walletAddress.isEmpty()) return;
        try {
            BigInteger balanceWei = myToken.balanceOf(walletAddress).send();
            
            BigDecimal balanceEth = new BigDecimal(balanceWei)
                    .divide(new BigDecimal("1000000000000000000"), 2, RoundingMode.HALF_UP);

            Member member = memberRepository.findByWalletaddressIgnoreCase(walletAddress);
            if (member != null) {
                member.setOmtBalance(balanceEth);
                memberRepository.save(member);
                log.info("🔄 [비동기] DB 잔액 업데이트 완료: {} ({} OMT)", walletAddress, balanceEth);
            }
        } catch (Exception e) {
            log.error("❌ 잔액 동기화 실패 ({}): {}", walletAddress, e.getMessage());
        }
    }

    /**
     * [기능 2] 마스터 지갑에서 전송
     */
    public String transferFromMaster(String toAddress, long amount) throws Exception {
        BigInteger decimalAmount = BigInteger.valueOf(amount).multiply(BigInteger.valueOf(10).pow(18));
        String txHash = myToken.transfer(toAddress, decimalAmount).send().getTransactionHash();
        
        this.syncBalanceAsync(toAddress);
        this.syncBalanceAsync(adminWalletAddress);
        
        return txHash;
    }

    /**
     * [기능 3] 기여 보상 시스템 (분산 락 적용)
     */
    public String rewardContribution(String userEmail, long rewardAmount) {
        // 주입된 빈이 있는지 확인
        RedissonClient redissonClient = redissonClientProvider.getIfAvailable();

        if (redissonClient == null) {
            log.warn("⚠️ Redis 연결 불가(또는 설정 안됨)로 락 없이 보상을 지급합니다: {}", userEmail);
            return executeRewardInternal(userEmail, rewardAmount);
        }

        String lockKey = "reward_lock:" + userEmail;
        RLock lock = redissonClient.getLock(lockKey);
        
        try {
            boolean isLocked = lock.tryLock(5, 10, TimeUnit.SECONDS);
            if (!isLocked) {
                log.warn("⚠️ 보상 지급 중복 요청 방지됨: {}", userEmail);
                return null;
            }
            return executeRewardInternal(userEmail, rewardAmount);

        } catch (InterruptedException e) {
            log.error("❌ 락 획득 대기 중 인터럽트 발생: {}", e.getMessage());
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private String executeRewardInternal(String userEmail, long rewardAmount) {
        try {
            Member member = memberRepository.findById(userEmail)
                    .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));
            
            String walletAddress = member.getWalletaddress();
            if (walletAddress == null || walletAddress.isEmpty()) {
                log.error("❌ 지갑 주소 없음: {}", userEmail);
                return null;
            }

            BigInteger decimalAmount = BigInteger.valueOf(rewardAmount).multiply(BigInteger.valueOf(10).pow(18));
            log.info("🚀 보상 전송 시작: {} -> {} OMT", userEmail, rewardAmount);
            
            String txHash = myToken.transfer(walletAddress, decimalAmount).send().getTransactionHash();
            log.info("✅ 전송 성공! 해시: {}", txHash);

            this.syncBalanceAsync(walletAddress);
            this.syncBalanceAsync(adminWalletAddress);

            return txHash;
        } catch (Exception e) {
            log.error("❌ 보상 지급 처리 오류: {}", e.getMessage());
            return null;
        }
    }
}
