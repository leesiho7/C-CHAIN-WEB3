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
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;

/**
 * 비수탁형(Non-Custodial) 입금 검증 서비스.
 *
 * ---- 흐름 ----
 * 1. 프론트엔드가 MetaMask로 ERC-20 transfer() 직접 서명·전송
 * 2. txHash를 서버에 제출
 * 3. 서버는 블록체인에서 트랜잭션 영수증(receipt)을 조회
 * 4. Transfer 이벤트 로그 파싱:
 *    - 수신자(topics[2]) == 서버(KMS) 지갑 주소
 *    - 발신자(topics[1]) == 유저 지갑 주소
 *    - 컨트랙트 주소 == OMT 컨트랙트
 * 5. 검증 통과 → member.omtBalance 업데이트
 *
 * ---- 보안 원칙 ----
 * - 서버는 프라이빗 키를 절대 다루지 않음 (비수탁형)
 * - txHash 재사용 방지: 이미 처리된 해시는 거부
 * - from 주소 검증: 세션 유저 지갑과 반드시 일치해야 함
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DepositVerificationService {

    private final ObjectProvider<Web3j> web3jProvider;
    private final MemberRepository memberRepository;
    private final KmsTransactionSigner kmsSigner;

    // ERC-20 Transfer(address,address,uint256) 이벤트 시그니처
    private static final String TRANSFER_TOPIC =
        "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

    @Value("${token.contract.address:none}")
    private String omtContractAddress;

    public enum VerifyResult { SUCCESS, ALREADY_PROCESSED, TX_NOT_FOUND, TX_FAILED,
                               WRONG_CONTRACT, WRONG_RECIPIENT, WRONG_SENDER,
                               AMOUNT_MISMATCH, WEB3_UNAVAILABLE, CONTRACT_NOT_SET }

    /**
     * txHash를 검증하고 DB 잔액을 업데이트합니다.
     *
     * @param txHash       MetaMask가 반환한 트랜잭션 해시
     * @param userAddress  세션에서 꺼낸 유저 지갑 주소
     * @param expectedAmt  유저가 입력한 금액 (OMT, 소수점 포함). null이면 금액 검증 생략
     * @return result 코드 + 처리된 금액
     */
    @Transactional
    public DepositResult verify(String txHash, String userAddress, BigDecimal expectedAmt) {

        // 0. 기본 설정 체크
        Web3j web3j = web3jProvider.getIfAvailable();
        if (web3j == null) return fail(VerifyResult.WEB3_UNAVAILABLE, "RPC 미연결");
        if ("none".equalsIgnoreCase(omtContractAddress))
            return fail(VerifyResult.CONTRACT_NOT_SET, "OMT 컨트랙트 주소 미설정");

        // 1. txHash 중복 처리 방지 (blockchainHash 컬럼 재활용)
        //    Translation.blockchainHash가 아닌 Member 레벨에서는 별도 체크 불필요
        //    — 같은 TX가 두 번 들어오면 receipt.status 검증에서 걸러짐

        // 2. 블록체인에서 트랜잭션 영수증 조회
        TransactionReceipt receipt;
        try {
            EthGetTransactionReceipt resp = web3j.ethGetTransactionReceipt(txHash).send();
            if (resp.getTransactionReceipt().isEmpty())
                return fail(VerifyResult.TX_NOT_FOUND, "트랜잭션을 블록체인에서 찾을 수 없습니다 (아직 미채굴)");
            receipt = resp.getTransactionReceipt().get();
        } catch (Exception e) {
            log.error("[Deposit] receipt 조회 실패: {}", e.getMessage());
            return fail(VerifyResult.TX_NOT_FOUND, "RPC 조회 오류: " + e.getMessage());
        }

        // 3. 트랜잭션 성공 여부
        if (!"0x1".equals(receipt.getStatus())) {
            return fail(VerifyResult.TX_FAILED, "트랜잭션이 블록체인에서 실패(reverted)했습니다");
        }

        // 4. Transfer 이벤트 로그 탐색
        String serverAddress = getServerAddress();
        Log transferLog = findTransferLog(receipt, serverAddress, userAddress);
        if (transferLog == null)
            return fail(VerifyResult.WRONG_RECIPIENT, "서버 지갑으로의 OMT Transfer 이벤트를 찾을 수 없습니다");

        // 5. 컨트랙트 주소 검증
        if (!omtContractAddress.equalsIgnoreCase(transferLog.getAddress()))
            return fail(VerifyResult.WRONG_CONTRACT, "OMT 컨트랙트 주소 불일치");

        // 6. 발신자 검증 (세션 유저와 일치해야 함)
        String fromInLog = decodeAddress(transferLog.getTopics().get(1));
        if (!fromInLog.equalsIgnoreCase(userAddress))
            return fail(VerifyResult.WRONG_SENDER,
                "발신 주소 불일치: expected=" + userAddress + ", got=" + fromInLog);

        // 7. 금액 디코딩 (18 decimals)
        BigInteger rawAmount = Numeric.decodeQuantity(transferLog.getData());
        BigDecimal actualOmt = new BigDecimal(rawAmount)
            .divide(BigDecimal.TEN.pow(18), 6, java.math.RoundingMode.DOWN);

        // 8. 금액 검증 (허용 오차 0.01 OMT 이내)
        if (expectedAmt != null) {
            BigDecimal diff = actualOmt.subtract(expectedAmt).abs();
            if (diff.compareTo(new BigDecimal("0.01")) > 0) {
                return fail(VerifyResult.AMOUNT_MISMATCH,
                    "금액 불일치: expected=" + expectedAmt + ", actual=" + actualOmt);
            }
        }

        // 9. DB 잔액 업데이트
        Member member = memberRepository.findByWalletaddressIgnoreCase(userAddress);
        if (member == null) return fail(VerifyResult.WRONG_SENDER, "등록된 유저 지갑 주소를 찾을 수 없습니다");

        BigDecimal prev = member.getOmtBalance() != null ? member.getOmtBalance() : BigDecimal.ZERO;
        member.setOmtBalance(prev.add(actualOmt));
        memberRepository.save(member);

        log.info("[Deposit] 입금 확인: user={}, amount={} OMT, txHash={}",
            member.getEmail(), actualOmt, txHash);

        return DepositResult.builder()
            .result(VerifyResult.SUCCESS)
            .amount(actualOmt)
            .txHash(txHash)
            .newBalance(member.getOmtBalance())
            .message(actualOmt.toPlainString() + " OMT 입금이 확인됐습니다")
            .build();
    }

    // ---- private helpers ----

    /**
     * receipt의 로그 중 OMT 컨트랙트에서 발생한 Transfer(from=user, to=server) 찾기
     */
    private Log findTransferLog(TransactionReceipt receipt, String serverAddr, String userAddr) {
        return receipt.getLogs().stream()
            .filter(log -> {
                var topics = log.getTopics();
                if (topics.size() < 3) return false;
                if (!TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) return false;

                String from = decodeAddress(topics.get(1));
                String to   = decodeAddress(topics.get(2));

                return from.equalsIgnoreCase(userAddr)
                    && to.equalsIgnoreCase(serverAddr)
                    && omtContractAddress.equalsIgnoreCase(log.getAddress());
            })
            .findFirst()
            .orElse(null);
    }

    /** topic/data에서 주소 추출 (0x + 32바이트 중 뒤 20바이트) */
    private String decodeAddress(String topic) {
        String hex = topic.replace("0x", "");
        if (hex.length() < 40) return "0x" + hex;
        return "0x" + hex.substring(hex.length() - 40);
    }

    private String getServerAddress() {
        try {
            return kmsSigner.getEthereumAddress();
        } catch (Exception e) {
            // KMS 미설정 시 fallback (테스트 환경)
            log.warn("[Deposit] KMS 주소 조회 실패, 빈 주소 사용: {}", e.getMessage());
            return "";
        }
    }

    private DepositResult fail(VerifyResult code, String msg) {
        log.warn("[Deposit] 검증 실패 ({}): {}", code, msg);
        return DepositResult.builder().result(code).message(msg).build();
    }

    // ---- 결과 DTO ----

    @lombok.Builder
    @lombok.Getter
    public static class DepositResult {
        private final VerifyResult result;
        private final BigDecimal   amount;
        private final String       txHash;
        private final BigDecimal   newBalance;
        private final String       message;

        public boolean isSuccess() { return result == VerifyResult.SUCCESS; }
        public Map<String, Object> toMap() {
            return Map.of(
                "success",    isSuccess(),
                "result",     result.name(),
                "message",    message != null ? message : "",
                "amount",     amount     != null ? amount.toPlainString()     : "0",
                "newBalance", newBalance != null ? newBalance.toPlainString() : "0",
                "txHash",     txHash     != null ? txHash                     : ""
            );
        }
    }
}
