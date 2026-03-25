package com.tem.cchain.wallet;

import com.tem.cchain.wallet.audit.AuditLogService;
import com.tem.cchain.wallet.audit.WalletOperation;
import com.tem.cchain.wallet.iam.IamRoleService;
import com.tem.cchain.wallet.iam.RequiresWalletRole;
import com.tem.cchain.wallet.iam.WalletAccessDeniedException;
import com.tem.cchain.wallet.iam.WalletRole;
import com.tem.cchain.wallet.kms.KmsTransactionSigner;
import com.tem.cchain.wallet.policy.PolicyEngine;
import com.tem.cchain.wallet.policy.dto.PolicyDecision;
import com.tem.cchain.wallet.policy.dto.PolicyRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.tx.gas.DefaultGasProvider;
import org.web3j.crypto.RawTransaction;
import org.web3j.utils.Convert;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * 엔터프라이즈 서버 지갑 서비스.
 *
 * ---- 보안 레이어 처리 순서 ----
 *
 * Request
 *   1. [@RequiresWalletRole AOP]
 *      → WalletRoleAspect가 가로채서 IAM Role 검증
 *      → 실패 시 WalletAccessDeniedException (Audit 기록)
 *
 *   2. [PolicyEngine.evaluate()]
 *      → OPA or FallbackPolicyEngine이 정책 평가
 *      → 실패 시 PolicyViolationException (Audit 기록)
 *
 *   3. [KmsTransactionSigner.signTransaction()]
 *      → AWS KMS에서 서명 (private key JVM에 없음)
 *      → 서명된 raw transaction hex 반환
 *
 *   4. [web3j.ethSendRawTransaction()]
 *      → 블록체인에 브로드캐스트
 *
 *   5. [AuditLogService.logTransfer()]
 *      → 성공/실패 감사 기록 (비동기, 독립 트랜잭션)
 *
 * ---- 기존 TokenService와 비교 ----
 * 기존: Credentials(private key) → myToken.transfer() → 완료
 * 신규: Role 검증 → 정책 검증 → KMS 서명 → 브로드캐스트 → 감사 기록
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnterpriseWalletService {

    private final ObjectProvider<Web3j> web3jProvider;
    private final KmsTransactionSigner kmsSigner;
    private final PolicyEngine policyEngine;
    private final IamRoleService iamRoleService;
    private final AuditLogService auditLogService;

    @Value("${token.contract.address:none}")
    private String omtContractAddress;

    // ERC-20 transfer 함수 시그니처: keccak256("transfer(address,uint256)")[0:4]
    private static final String ERC20_TRANSFER_SELECTOR = "a9059cbb";

    // =========================================================
    // 1. OMT 토큰 전송 (마스터 → 지정 주소)
    // =========================================================

    /**
     * 마스터 지갑에서 OMT 토큰을 전송합니다.
     *
     * @RequiresWalletRole(MASTER): MASTER 이상 역할만 호출 가능
     * → WalletRoleAspect가 AOP로 검증 (이 메서드 코드에 역할 검증 코드 없음!)
     *
     * @param toAddress 수신 주소 (0x...)
     * @param amount    전송량 (OMT 단위, 예: 100.0)
     * @return 트랜잭션 해시 (0x...)
     */
    @RequiresWalletRole(WalletRole.MASTER)
    public String transferOmt(String toAddress, BigDecimal amount) {
        log.info("[Wallet] OMT 전송 시작: to={}, amount={}", toAddress, amount);

        // Step 1: 정책 검사
        PolicyDecision decision = evaluatePolicy("TRANSFER", toAddress, amount);
        if (!decision.isAllow()) {
            auditLogService.logPolicyDenied(toAddress, amount, decision.getReason());
            throw new PolicyViolationException(decision.getReason());
        }

        try {
            // Step 2: KMS로 서명 후 브로드캐스트
            String txHash = signAndSendErc20Transfer(toAddress, amount);

            // Step 3: 감사 로그 기록 (비동기)
            auditLogService.logTransfer(toAddress, amount, "OMT", txHash,
                WalletOperation.MASTER_TRANSFER);

            log.info("[Wallet] OMT 전송 완료: txHash={}", txHash);
            return txHash;

        } catch (PolicyViolationException e) {
            throw e;
        } catch (Exception e) {
            auditLogService.logFailure(WalletOperation.MASTER_TRANSFER, toAddress, amount,
                e.getMessage());
            throw new WalletTransactionException("OMT 전송 실패: " + e.getMessage(), e);
        }
    }

    // =========================================================
    // 2. 기여 보상 지급 (Reward)
    // =========================================================

    /**
     * 기여 보상을 지급합니다.
     *
     * @RequiresWalletRole(REWARD): REWARD 이상 역할 (MASTER도 가능)
     *
     * @param toAddress   수신 주소
     * @param rewardAmount 보상량 (OMT 단위)
     * @return 트랜잭션 해시
     */
    @RequiresWalletRole(WalletRole.REWARD)
    public String payReward(String toAddress, BigDecimal rewardAmount) {
        log.info("[Wallet] 보상 지급 시작: to={}, amount={}", toAddress, rewardAmount);

        PolicyDecision decision = evaluatePolicy("REWARD", toAddress, rewardAmount);
        if (!decision.isAllow()) {
            auditLogService.logPolicyDenied(toAddress, rewardAmount, decision.getReason());
            throw new PolicyViolationException(decision.getReason());
        }

        try {
            String txHash = signAndSendErc20Transfer(toAddress, rewardAmount);
            auditLogService.logTransfer(toAddress, rewardAmount, "OMT", txHash,
                WalletOperation.REWARD_PAYMENT);
            log.info("[Wallet] 보상 지급 완료: txHash={}", txHash);
            return txHash;

        } catch (PolicyViolationException e) {
            throw e;
        } catch (Exception e) {
            auditLogService.logFailure(WalletOperation.REWARD_PAYMENT, toAddress, rewardAmount,
                e.getMessage());
            throw new WalletTransactionException("보상 지급 실패: " + e.getMessage(), e);
        }
    }

    // =========================================================
    // 3. 잔액 조회 (모든 역할 허용)
    // =========================================================

    /**
     * ETH 잔액 조회. READ_ONLY 이상 모든 역할 허용.
     */
    @RequiresWalletRole(WalletRole.READ_ONLY)
    public BigDecimal getEthBalance(String walletAddress) {
        Web3j web3j = web3jProvider.getIfAvailable();
        if (web3j == null) return BigDecimal.ZERO;
        try {
            BigInteger wei = web3j.ethGetBalance(walletAddress, DefaultBlockParameterName.LATEST)
                .send().getBalance();
            BigDecimal eth = Convert.fromWei(wei.toString(), Convert.Unit.ETHER);
            auditLogService.logTransfer(walletAddress, eth, "ETH", null,
                WalletOperation.BALANCE_CHECK);
            return eth;
        } catch (Exception e) {
            log.error("[Wallet] ETH 잔액 조회 실패: {}", e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    // =========================================================
    // 비동기 잔액 동기화 (내부 호출)
    // =========================================================

    @Async
    public void syncBalanceAsync(String walletAddress) {
        // 기존 TokenService.syncBalanceAsync 역할과 동일
        // 별도 Async 스레드에서 실행
        log.debug("[Wallet] 비동기 잔액 동기화: {}", walletAddress);
        auditLogService.logTransfer(walletAddress, null, "OMT", null,
            WalletOperation.BALANCE_SYNC);
    }

    // =========================================================
    // private: 핵심 서명 + 전송 로직
    // =========================================================

    /**
     * ERC-20 transfer 트랜잭션을 KMS로 서명하고 블록체인에 전송합니다.
     *
     * ---- ERC-20 transfer 함수 데이터 인코딩 ----
     * 함수 셀렉터 (4바이트): keccak256("transfer(address,uint256)")[0:4] = 0xa9059cbb
     * to address  (32바이트): 왼쪽에 0 패딩
     * amount      (32바이트): 왼쪽에 0 패딩 (Wei 단위)
     *
     * 합계: 68바이트 hex = 0x + 136 characters
     */
    private String signAndSendErc20Transfer(String toAddress, BigDecimal amount) throws Exception {
        Web3j web3j = web3jProvider.getIfAvailable();
        if (web3j == null) throw new WalletTransactionException("Web3j 미연결 상태입니다. RPC URL을 확인하세요.");

        String serverAddress = kmsSigner.getEthereumAddress();

        // Nonce 조회 (트랜잭션 중복 방지)
        EthGetTransactionCount nonceResponse = web3j
            .ethGetTransactionCount(serverAddress, DefaultBlockParameterName.PENDING)
            .send();
        BigInteger nonce = nonceResponse.getTransactionCount();

        // Amount: OMT → Wei (× 10^18)
        BigInteger amountWei = Convert.toWei(amount, Convert.Unit.ETHER).toBigInteger();

        // ERC-20 calldata 인코딩
        String calldata = encodeErc20Transfer(toAddress, amountWei);

        // RawTransaction 생성
        RawTransaction rawTransaction = RawTransaction.createTransaction(
            nonce,
            DefaultGasProvider.GAS_PRICE,
            DefaultGasProvider.GAS_LIMIT,
            omtContractAddress,
            BigInteger.ZERO,   // ETH value = 0 (토큰 전송이므로)
            calldata
        );

        // KMS 서명 (private key 없이!)
        String signedTxHex = kmsSigner.signTransaction(rawTransaction);

        // 브로드캐스트
        EthSendTransaction sendResult = web3j
            .ethSendRawTransaction(signedTxHex)
            .send();

        if (sendResult.hasError()) {
            throw new WalletTransactionException(
                "블록체인 전송 오류: " + sendResult.getError().getMessage());
        }

        return sendResult.getTransactionHash();
    }

    /**
     * ERC-20 transfer(address,uint256) calldata 인코딩.
     * ABI 인코딩 규칙: 각 파라미터는 32바이트(64 hex chars)로 패딩
     */
    private String encodeErc20Transfer(String toAddress, BigInteger amountWei) {
        // to 주소: 0x 제거 후 64자리로 왼쪽 패딩
        String paddedTo = String.format("%064x",
            new java.math.BigInteger(toAddress.replace("0x", ""), 16));

        // amount: 64자리로 왼쪽 패딩
        String paddedAmount = String.format("%064x", amountWei);

        return "0x" + ERC20_TRANSFER_SELECTOR + paddedTo + paddedAmount;
    }

    /**
     * PolicyEngine에 평가 요청을 보냅니다.
     * 일일 누적 전송량을 AuditLog에서 조회하여 PolicyRequest에 포함합니다.
     */
    private PolicyDecision evaluatePolicy(String operationType, String toAddress,
                                          BigDecimal amount) {
        String callerEmail = getCurrentEmail();
        BigDecimal dailyTotal = auditLogService.getDailyTransferTotal(callerEmail);

        return policyEngine.evaluate(PolicyRequest.builder()
            .callerRole(iamRoleService.getCurrentRole().name())
            .toAddress(toAddress)
            .amount(amount)
            .tokenType("OMT")
            .dailyTotal(dailyTotal)
            .operationType(operationType)
            .build());
    }

    private String getCurrentEmail() {
        try {
            return org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getName();
        } catch (Exception e) {
            return "system";
        }
    }

    // =========================================================
    // 내부 예외 클래스
    // =========================================================

    public static class PolicyViolationException extends RuntimeException {
        public PolicyViolationException(String reason) {
            super("정책 위반: " + reason);
        }
    }

    public static class WalletTransactionException extends RuntimeException {
        public WalletTransactionException(String message) { super(message); }
        public WalletTransactionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
