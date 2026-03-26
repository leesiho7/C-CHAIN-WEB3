package com.tem.cchain.controller;

import com.tem.cchain.entity.Member;
import com.tem.cchain.entity.Translation;
import com.tem.cchain.repository.TranslationRepository;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.utils.Convert;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * 일반 유저 전용 지갑 REST API.
 * wallet-server.html (핀테크 사용자 대시보드)에서 호출합니다.
 *
 *  GET /api/user/wallet/balance  → 유저 MetaMask 지갑 OMT/ETH 잔액
 *  GET /api/user/rewards         → 보상 수령 타임라인 (검증 완료된 번역)
 *  GET /api/user/stats           → 활동 통계 (제출/승인/총 수령)
 */
@Slf4j
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserWalletApiController {

    private final TranslationRepository translationRepository;
    private final ObjectProvider<Web3j> web3jProvider;

    private static final String BALANCE_OF_SELECTOR = "0x70a08231";
    private static final BigDecimal OMT_REWARD_PER_CASE = new BigDecimal("10");

    @Value("${token.contract.address:none}")
    private String omtContractAddress;

    // =====================================================================
    // GET /api/user/wallet/balance
    // 유저의 MetaMask 지갑에 있는 OMT/ETH 잔액을 반환합니다.
    // =====================================================================

    @GetMapping("/wallet/balance")
    public ResponseEntity<Map<String, Object>> balance(HttpSession session) {
        Member member = getLoginMember(session);
        if (member == null) return ResponseEntity.status(401).build();

        String walletAddress = member.getWalletaddress();
        String omtBalance = "0";
        String ethBalance = "0";
        boolean walletConnected = walletAddress != null && !walletAddress.isBlank();

        if (walletConnected) {
            Web3j web3j = web3jProvider.getIfAvailable();
            if (web3j != null) {
                ethBalance = fetchEth(web3j, walletAddress);
                omtBalance = fetchOmt(web3j, walletAddress);
            }
        }

        return ResponseEntity.ok(Map.of(
            "userid",          member.getUserid(),
            "walletAddress",   walletAddress != null ? walletAddress : "",
            "walletConnected", walletConnected,
            "omtBalance",      omtBalance,
            "ethBalance",      ethBalance
        ));
    }

    // =====================================================================
    // GET /api/user/rewards
    // 유저의 보상 수령 내역 (verifiedAt 있고 blockchainHash 있는 Translation)
    // =====================================================================

    @GetMapping("/rewards")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> rewards(HttpSession session) {
        Member member = getLoginMember(session);
        if (member == null) return ResponseEntity.status(401).build();

        List<Translation> all = translationRepository.findByUserOrderByVerifiedAtDesc(member);

        List<Map<String, Object>> result = all.stream()
            .filter(t -> t.getVerifiedAt() != null)
            .map(t -> Map.<String, Object>of(
                "docTitle",    t.getDocument() != null ? t.getDocument().getTitleCn() : "—",
                "amount",      OMT_REWARD_PER_CASE.toPlainString(),
                "txHash",      t.getBlockchainHash() != null ? t.getBlockchainHash() : "",
                "rewarded",    t.getBlockchainHash() != null,
                "verifiedAt",  t.getVerifiedAt()
                                    .atOffset(ZoneOffset.UTC).toString()
            ))
            .toList();

        return ResponseEntity.ok(result);
    }

    // =====================================================================
    // GET /api/user/stats
    // 활동 통계: 총 제출, 총 승인, 누적 수령 OMT
    // =====================================================================

    @GetMapping("/stats")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> stats(HttpSession session) {
        Member member = getLoginMember(session);
        if (member == null) return ResponseEntity.status(401).build();

        long submitted = translationRepository.countByUser(member);
        long verified  = translationRepository.countByUserAndVerifiedAtIsNotNull(member);

        // 블록체인 해시가 있는 = 실제로 토큰이 지급된 건
        long rewarded = translationRepository.findByUserOrderByVerifiedAtDesc(member).stream()
            .filter(t -> t.getBlockchainHash() != null)
            .count();

        BigDecimal totalOmt = OMT_REWARD_PER_CASE.multiply(BigDecimal.valueOf(rewarded));

        return ResponseEntity.ok(Map.of(
            "totalSubmitted", submitted,
            "totalVerified",  verified,
            "totalRewarded",  rewarded,
            "totalOmt",       totalOmt.toPlainString()
        ));
    }

    // =====================================================================
    // private helpers
    // =====================================================================

    private String fetchEth(Web3j web3j, String address) {
        try {
            BigInteger wei = web3j
                .ethGetBalance(address, DefaultBlockParameterName.LATEST)
                .send().getBalance();
            return Convert.fromWei(wei.toString(), Convert.Unit.ETHER)
                .setScale(4, RoundingMode.DOWN).toPlainString();
        } catch (Exception e) {
            log.warn("[UserWalletAPI] ETH 잔액 조회 실패: {}", e.getMessage());
            return "0";
        }
    }

    private String fetchOmt(Web3j web3j, String walletAddress) {
        if ("none".equalsIgnoreCase(omtContractAddress)) return "0";
        try {
            String paddedAddr = String.format("%064x",
                new BigInteger(walletAddress.replace("0x", ""), 16));
            String callData = BALANCE_OF_SELECTOR + paddedAddr;

            Transaction call = Transaction.createEthCallTransaction(
                walletAddress, omtContractAddress, callData);

            String hexResult = web3j.ethCall(call, DefaultBlockParameterName.LATEST)
                .send().getValue();

            if (hexResult == null || hexResult.equals("0x")) return "0";
            BigInteger raw = Numeric.decodeQuantity(hexResult);
            return Convert.fromWei(raw.toString(), Convert.Unit.ETHER)
                .setScale(2, RoundingMode.DOWN).toPlainString();
        } catch (Exception e) {
            log.warn("[UserWalletAPI] OMT 잔액 조회 실패: {}", e.getMessage());
            return "0";
        }
    }

    private Member getLoginMember(HttpSession session) {
        return (Member) session.getAttribute("loginMember");
    }
}
