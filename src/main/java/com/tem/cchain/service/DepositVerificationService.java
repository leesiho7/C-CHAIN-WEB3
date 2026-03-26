package com.tem.cchain.service;

import com.tem.cchain.entity.Member;
import com.tem.cchain.repository.MemberRepository;
import com.tem.cchain.wallet.kms.KmsTransactionSigner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.*;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;

/**
 * 비수탁형(Non-Custodial) 입금 검증 서비스.
 *
 * 지원 토큰:
 *   - OMT  : ERC-20 Transfer 이벤트 검증
 *   - USDT : ERC-20 Transfer 이벤트 검증 (Sepolia USDC 컨트랙트)
 *   - ETH  : native ETH 트랜잭션 검증 (Transfer 이벤트 없음)
 *
 * 보안 원칙:
 *   - 서버는 프라이빗 키를 절대 보관하지 않음 (비수탁형)
 *   - from 주소 = 세션 유저 지갑 주소 (반드시 일치)
 *   - to   주소 = KMS 서버 지갑 주소  (반드시 일치)
 *   - 지갑 소유권 인증(walletVerified) 완료 유저만 입금 허용
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DepositVerificationService {

    private final ObjectProvider<Web3j> web3jProvider;
    private final MemberRepository memberRepository;
    private final KmsTransactionSigner kmsSigner;

    private static final String TRANSFER_TOPIC =
        "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

    @Value("${token.contract.address:none}")
    private String omtContractAddress;

    @Value("${wallet.deposit.usdt-contract:0x1c7d4b196cb0c7b01d743fbc6116a902379c7238}")
    private String usdtContractAddress;

    public enum TokenType { OMT, USDT, ETH }

    public enum VerifyResult {
        SUCCESS, ALREADY_PROCESSED, TX_NOT_FOUND, TX_FAILED,
        WRONG_CONTRACT, WRONG_RECIPIENT, WRONG_SENDER,
        AMOUNT_MISMATCH, WEB3_UNAVAILABLE, CONTRACT_NOT_SET,
        WALLET_NOT_VERIFIED
    }

    // =====================================================================
    // 메인 검증 진입점
    // =====================================================================

    @Transactional
    public DepositResult verify(String txHash, String userAddress,
                                BigDecimal expectedAmt, TokenType tokenType) {
        Web3j web3j = web3jProvider.getIfAvailable();
        if (web3j == null) return fail(VerifyResult.WEB3_UNAVAILABLE, "RPC 미연결");

        // 지갑 소유권 인증 확인 (ETH 네이티브 입금도 동일)
        Member member = memberRepository.findByWalletaddressIgnoreCase(userAddress);
        if (member == null)
            return fail(VerifyResult.WRONG_SENDER, "등록된 지갑 주소를 찾을 수 없습니다");
        if (!Boolean.TRUE.equals(member.getWalletVerified()))
            return fail(VerifyResult.WALLET_NOT_VERIFIED,
                "지갑 소유권 인증이 필요합니다. '지갑 인증' 버튼을 먼저 눌러주세요.");

        return switch (tokenType) {
            case ETH  -> verifyEth(web3j, txHash, userAddress, expectedAmt, member);
            case USDT -> verifyErc20(web3j, txHash, userAddress, expectedAmt,
                                     usdtContractAddress, TokenType.USDT, member);
            case OMT  -> verifyErc20(web3j, txHash, userAddress, expectedAmt,
                                     omtContractAddress, TokenType.OMT, member);
        };
    }

    /** SSE 감지 경로(토큰 타입 자동 판별)용 오버로드 */
    @Transactional
    public DepositResult verify(String txHash, String userAddress, BigDecimal expectedAmt) {
        return verify(txHash, userAddress, expectedAmt, TokenType.OMT);
    }

    // =====================================================================
    // ETH 네이티브 입금 검증
    // =====================================================================

    private DepositResult verifyEth(Web3j web3j, String txHash,
                                    String userAddress, BigDecimal expectedAmt,
                                    Member member) {
        String serverAddress = getServerAddress();

        // 1. 영수증 조회 (status 확인)
        TransactionReceipt receipt = getReceipt(web3j, txHash);
        if (receipt == null)
            return fail(VerifyResult.TX_NOT_FOUND, "트랜잭션을 블록체인에서 찾을 수 없습니다");
        if (!"0x1".equals(receipt.getStatus()))
            return fail(VerifyResult.TX_FAILED, "트랜잭션이 실패(reverted)했습니다");

        // 2. 트랜잭션 상세 조회 (value, to, from)
        EthTransaction ethTx;
        try {
            ethTx = web3j.ethGetTransactionByHash(txHash).send();
        } catch (Exception e) {
            return fail(VerifyResult.TX_NOT_FOUND, "트랜잭션 조회 오류: " + e.getMessage());
        }
        if (ethTx.getTransaction().isEmpty())
            return fail(VerifyResult.TX_NOT_FOUND, "트랜잭션 데이터를 찾을 수 없습니다");

        org.web3j.protocol.core.methods.response.Transaction tx =
            ethTx.getTransaction().get();

        // 3. 수신자 검증 (서버 지갑)
        if (!serverAddress.equalsIgnoreCase(tx.getTo()))
            return fail(VerifyResult.WRONG_RECIPIENT, "수신 주소가 서버 지갑과 다릅니다");

        // 4. 발신자 검증 (세션 유저)
        if (!userAddress.equalsIgnoreCase(tx.getFrom()))
            return fail(VerifyResult.WRONG_SENDER, "발신 주소가 유저 지갑과 다릅니다");

        // 5. 금액 디코딩 (Wei → ETH)
        BigDecimal actualEth = new BigDecimal(tx.getValue())
            .divide(BigDecimal.TEN.pow(18), 8, java.math.RoundingMode.DOWN);

        if (expectedAmt != null) {
            BigDecimal diff = actualEth.subtract(expectedAmt).abs();
            if (diff.compareTo(new BigDecimal("0.0001")) > 0)
                return fail(VerifyResult.AMOUNT_MISMATCH,
                    "금액 불일치: expected=" + expectedAmt + ", actual=" + actualEth);
        }

        // 6. DB 업데이트
        BigDecimal prev = member.getEthDepositBalance() != null
            ? member.getEthDepositBalance() : BigDecimal.ZERO;
        member.setEthDepositBalance(prev.add(actualEth));
        memberRepository.save(member);

        log.info("[Deposit] ETH 입금 확인: user={}, amount={} ETH, tx={}",
            member.getEmail(), actualEth, txHash);

        return DepositResult.builder()
            .result(VerifyResult.SUCCESS)
            .tokenType(TokenType.ETH)
            .amount(actualEth)
            .txHash(txHash)
            .newBalance(member.getEthDepositBalance())
            .message(actualEth.toPlainString() + " ETH 입금이 확인됐습니다")
            .build();
    }

    // =====================================================================
    // ERC-20 (OMT / USDT) 입금 검증
    // =====================================================================

    private DepositResult verifyErc20(Web3j web3j, String txHash,
                                      String userAddress, BigDecimal expectedAmt,
                                      String contractAddress, TokenType tokenType,
                                      Member member) {
        if ("none".equalsIgnoreCase(contractAddress))
            return fail(VerifyResult.CONTRACT_NOT_SET, tokenType + " 컨트랙트 주소 미설정");

        String serverAddress = getServerAddress();

        // 1. 영수증 조회
        TransactionReceipt receipt = getReceipt(web3j, txHash);
        if (receipt == null)
            return fail(VerifyResult.TX_NOT_FOUND, "트랜잭션을 블록체인에서 찾을 수 없습니다");
        if (!"0x1".equals(receipt.getStatus()))
            return fail(VerifyResult.TX_FAILED, "트랜잭션이 실패(reverted)했습니다");

        // 2. Transfer 이벤트 로그 탐색
        Log transferLog = findTransferLog(receipt, contractAddress, serverAddress, userAddress);
        if (transferLog == null)
            return fail(VerifyResult.WRONG_RECIPIENT,
                contractAddress + " 컨트랙트의 Transfer(user→server) 이벤트를 찾을 수 없습니다");

        // 3. 발신자 재검증
        String fromInLog = decodeAddress(transferLog.getTopics().get(1));
        if (!fromInLog.equalsIgnoreCase(userAddress))
            return fail(VerifyResult.WRONG_SENDER,
                "발신 주소 불일치: " + fromInLog + " ≠ " + userAddress);

        // 4. 금액 디코딩
        int decimals = (tokenType == TokenType.USDT) ? 6 : 18; // USDC는 6 decimals
        BigInteger rawAmt = Numeric.decodeQuantity(transferLog.getData());
        BigDecimal actual = new BigDecimal(rawAmt)
            .divide(BigDecimal.TEN.pow(decimals), 6, java.math.RoundingMode.DOWN);

        if (expectedAmt != null) {
            BigDecimal diff = actual.subtract(expectedAmt).abs();
            BigDecimal tolerance = tokenType == TokenType.USDT
                ? new BigDecimal("0.01") : new BigDecimal("0.01");
            if (diff.compareTo(tolerance) > 0)
                return fail(VerifyResult.AMOUNT_MISMATCH,
                    "금액 불일치: expected=" + expectedAmt + ", actual=" + actual);
        }

        // 5. DB 업데이트
        BigDecimal newBalance;
        if (tokenType == TokenType.USDT) {
            BigDecimal prev = member.getUsdtBalance() != null
                ? member.getUsdtBalance() : BigDecimal.ZERO;
            member.setUsdtBalance(prev.add(actual));
            newBalance = member.getUsdtBalance();
        } else {
            BigDecimal prev = member.getOmtBalance() != null
                ? member.getOmtBalance() : BigDecimal.ZERO;
            member.setOmtBalance(prev.add(actual));
            newBalance = member.getOmtBalance();
        }
        memberRepository.save(member);

        log.info("[Deposit] {} 입금 확인: user={}, amount={}, tx={}",
            tokenType, member.getEmail(), actual, txHash);

        return DepositResult.builder()
            .result(VerifyResult.SUCCESS)
            .tokenType(tokenType)
            .amount(actual)
            .txHash(txHash)
            .newBalance(newBalance)
            .message(actual.toPlainString() + " " + tokenType + " 입금이 확인됐습니다")
            .build();
    }

    // =====================================================================
    // private helpers
    // =====================================================================

    private TransactionReceipt getReceipt(Web3j web3j, String txHash) {
        try {
            EthGetTransactionReceipt resp = web3j.ethGetTransactionReceipt(txHash).send();
            return resp.getTransactionReceipt().orElse(null);
        } catch (Exception e) {
            log.error("[Deposit] receipt 조회 실패: {}", e.getMessage());
            return null;
        }
    }

    private Log findTransferLog(TransactionReceipt receipt, String contractAddr,
                                String serverAddr, String userAddr) {
        return receipt.getLogs().stream()
            .filter(log -> {
                var topics = log.getTopics();
                if (topics.size() < 3) return false;
                if (!TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) return false;
                String from = decodeAddress(topics.get(1));
                String to   = decodeAddress(topics.get(2));
                return from.equalsIgnoreCase(userAddr)
                    && to.equalsIgnoreCase(serverAddr)
                    && contractAddr.equalsIgnoreCase(log.getAddress());
            })
            .findFirst().orElse(null);
    }

    private String decodeAddress(String topic) {
        String hex = topic.replace("0x", "");
        return "0x" + (hex.length() >= 40 ? hex.substring(hex.length() - 40) : hex);
    }

    private String getServerAddress() {
        try {
            return kmsSigner.isAvailable() ? kmsSigner.getEthereumAddress() : "";
        } catch (Exception e) {
            log.warn("[Deposit] KMS 주소 조회 실패: {}", e.getMessage());
            return "";
        }
    }

    private DepositResult fail(VerifyResult code, String msg) {
        log.warn("[Deposit] 검증 실패 ({}): {}", code, msg);
        return DepositResult.builder().result(code).message(msg).build();
    }

    // =====================================================================
    // 결과 DTO
    // =====================================================================

    @lombok.Builder
    @lombok.Getter
    public static class DepositResult {
        private final VerifyResult result;
        private final TokenType    tokenType;
        private final BigDecimal   amount;
        private final String       txHash;
        private final BigDecimal   newBalance;
        private final String       message;

        public boolean isSuccess() { return result == VerifyResult.SUCCESS; }

        public Map<String, Object> toMap() {
            return Map.of(
                "success",    isSuccess(),
                "result",     result.name(),
                "message",    message    != null ? message    : "",
                "tokenType",  tokenType  != null ? tokenType.name() : "",
                "amount",     amount     != null ? amount.toPlainString()     : "0",
                "newBalance", newBalance != null ? newBalance.toPlainString() : "0",
                "txHash",     txHash     != null ? txHash                     : ""
            );
        }
    }
}
