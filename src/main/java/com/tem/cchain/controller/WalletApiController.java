package com.tem.cchain.controller;

import com.tem.cchain.entity.Member;
import com.tem.cchain.wallet.audit.AuditEvent;
import com.tem.cchain.wallet.audit.AuditRepository;
import com.tem.cchain.wallet.kms.KmsTransactionSigner;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.utils.Convert;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * 기업형 커스터디 지갑 대시보드용 REST API.
 * wallet-dashboard.html에서 호출하는 두 엔드포인트를 제공합니다.
 *
 *  GET /api/wallet/server/balance  → KMS 서버 지갑 주소 + OMT/ETH 잔액
 *  GET /api/wallet/audit/recent    → 최근 감사 이벤트 목록
 */
@Slf4j
@RestController
@RequestMapping("/api/wallet")
@RequiredArgsConstructor
public class WalletApiController {

    private final KmsTransactionSigner kmsSigner;
    private final AuditRepository auditRepository;
    private final ObjectProvider<Web3j> web3jProvider;

    // ERC-20 balanceOf(address) 함수 셀렉터
    private static final String BALANCE_OF_SELECTOR = "0x70a08231";

    @Value("${token.contract.address:none}")
    private String omtContractAddress;

    // ================================================================
    // GET /api/wallet/server/balance
    // KMS 서버 지갑의 주소, ETH 잔액, OMT 잔액을 반환합니다.
    // ================================================================

    @GetMapping("/server/balance")
    public ResponseEntity<Map<String, Object>> serverBalance(HttpSession session) {
        if (!isAdmin(session)) {
            return ResponseEntity.status(403).body(Map.of("error", "권한 없음"));
        }

        String kmsAddress = "N/A";
        String ethBalance = "0";
        String omtBalance = "0";
        boolean kmsAvailable = kmsSigner.isAvailable();

        if (kmsAvailable) {
            try {
                kmsAddress = kmsSigner.getEthereumAddress();
            } catch (Exception e) {
                log.warn("[WalletAPI] KMS 주소 조회 실패: {}", e.getMessage());
                kmsAvailable = false;
            }
        }

        Web3j web3j = web3jProvider.getIfAvailable();
        if (web3j != null && !"N/A".equals(kmsAddress)) {
            ethBalance = fetchEthBalance(web3j, kmsAddress);
            omtBalance = fetchOmtBalance(web3j, kmsAddress);
        }

        return ResponseEntity.ok(Map.of(
            "kmsAddress", kmsAddress,
            "ethBalance", ethBalance,
            "omtBalance", omtBalance,
            "kmsAvailable", kmsAvailable
        ));
    }

    // ================================================================
    // GET /api/wallet/audit/recent?limit=10
    // 최근 감사 이벤트 목록을 반환합니다.
    // ================================================================

    @GetMapping("/audit/recent")
    public ResponseEntity<List<Map<String, Object>>> recentAudit(
            HttpSession session,
            @RequestParam(defaultValue = "10") int limit) {

        if (!isAdmin(session)) {
            return ResponseEntity.status(403).build();
        }

        int safeLimit = Math.min(limit, 50);
        List<AuditEvent> events = auditRepository.findAllByOrderByOccurredAtDesc(
            PageRequest.of(0, safeLimit)
        );

        List<Map<String, Object>> result = events.stream()
            .map(e -> Map.<String, Object>of(
                "id",          e.getId() != null ? e.getId() : 0,
                "operation",   e.getOperation() != null ? e.getOperation().name() : "-",
                "result",      e.getResult() != null ? e.getResult() : "-",
                "toAddress",   e.getToAddress() != null ? e.getToAddress() : "-",
                "amount",      e.getAmount() != null ? e.getAmount().toPlainString() : "0",
                "tokenType",   e.getTokenType() != null ? e.getTokenType() : "-",
                "txHash",      e.getTxHash() != null ? e.getTxHash() : "-",
                "occurredAt",  e.getOccurredAt() != null
                                   ? e.getOccurredAt().atOffset(ZoneOffset.UTC).toString()
                                   : "-"
            ))
            .toList();

        return ResponseEntity.ok(result);
    }

    // ================================================================
    // private helpers
    // ================================================================

    private String fetchEthBalance(Web3j web3j, String address) {
        try {
            BigInteger wei = web3j
                .ethGetBalance(address, DefaultBlockParameterName.LATEST)
                .send().getBalance();
            return Convert.fromWei(wei.toString(), Convert.Unit.ETHER)
                .setScale(6, java.math.RoundingMode.DOWN)
                .toPlainString();
        } catch (Exception e) {
            log.warn("[WalletAPI] ETH 잔액 조회 실패: {}", e.getMessage());
            return "0";
        }
    }

    private String fetchOmtBalance(Web3j web3j, String walletAddress) {
        if ("none".equalsIgnoreCase(omtContractAddress)) return "0";
        try {
            // balanceOf(address) ABI 인코딩
            String paddedAddr = String.format("%064x",
                new BigInteger(walletAddress.replace("0x", ""), 16));
            String callData = BALANCE_OF_SELECTOR + paddedAddr;

            Transaction call = Transaction.createEthCallTransaction(
                walletAddress, omtContractAddress, callData);

            String hexResult = web3j.ethCall(call, DefaultBlockParameterName.LATEST)
                .send().getValue();

            if (hexResult == null || hexResult.equals("0x")) return "0";

            BigInteger rawBalance = Numeric.decodeQuantity(hexResult);
            // OMT는 18 decimals
            return Convert.fromWei(rawBalance.toString(), Convert.Unit.ETHER)
                .setScale(4, java.math.RoundingMode.DOWN)
                .toPlainString();
        } catch (Exception e) {
            log.warn("[WalletAPI] OMT 잔액 조회 실패: {}", e.getMessage());
            return "0";
        }
    }

    private boolean isAdmin(HttpSession session) {
        Member m = (Member) session.getAttribute("loginMember");
        return m != null && "admin@cchain.com".equals(m.getEmail());
    }
}
