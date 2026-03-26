package com.tem.cchain.controller;

import com.tem.cchain.entity.Member;
import com.tem.cchain.service.DepositVerificationService;
import com.tem.cchain.wallet.kms.KmsTransactionSigner;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * 비수탁형 OMT 입금 API.
 *
 * POST /api/deposit/verify         → txHash 검증 + DB 업데이트
 * GET  /api/deposit/server-address → 서버(KMS) 지갑 주소 반환
 * GET  /api/deposit/stream         → SSE: 서버 지갑으로 입금 감지 실시간 푸시
 */
@Slf4j
@RestController
@RequestMapping("/api/deposit")
@RequiredArgsConstructor
public class DepositController {

    private final DepositVerificationService verificationService;
    private final KmsTransactionSigner kmsSigner;
    private final ObjectProvider<Web3j> web3jProvider;

    // SSE: 입금 대기 중인 세션별 Emitter 저장
    private final Map<String, SseEmitter> depositEmitters = new ConcurrentHashMap<>();

    private static final String TRANSFER_TOPIC =
        "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

    @Value("${token.contract.address:none}")
    private String omtContractAddress;

    // =====================================================================
    // 1. 서버 지갑 주소 조회 (프론트가 입금 대상 주소를 알아야 함)
    // =====================================================================

    @GetMapping("/server-address")
    public ResponseEntity<Map<String, Object>> serverAddress(HttpSession session) {
        if (getLoginMember(session) == null)
            return ResponseEntity.status(401).build();

        try {
            String addr = kmsSigner.isAvailable() ? kmsSigner.getEthereumAddress() : "";
            return ResponseEntity.ok(Map.of(
                "serverAddress",  addr,
                "kmsAvailable",   kmsSigner.isAvailable(),
                "omtContract",    omtContractAddress
            ));
        } catch (Exception e) {
            log.warn("[Deposit] 서버 주소 조회 실패: {}", e.getMessage());
            return ResponseEntity.ok(Map.of(
                "serverAddress", "",
                "kmsAvailable",  false,
                "omtContract",   omtContractAddress
            ));
        }
    }

    // =====================================================================
    // 2. txHash 검증 + DB 잔액 업데이트
    // =====================================================================

    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verify(
            @RequestBody Map<String, String> body,
            HttpSession session) {

        Member member = getLoginMember(session);
        if (member == null)
            return ResponseEntity.status(401).body(Map.of("message", "로그인이 필요합니다"));

        String walletAddress = member.getWalletaddress();
        if (walletAddress == null || walletAddress.isBlank())
            return ResponseEntity.badRequest()
                .body(Map.of("message", "먼저 MetaMask 지갑을 연결해 주세요"));

        String txHash = body.get("txHash");
        if (txHash == null || txHash.isBlank())
            return ResponseEntity.badRequest().body(Map.of("message", "txHash가 없습니다"));

        String amtStr = body.get("amount");
        BigDecimal expectedAmt = null;
        if (amtStr != null && !amtStr.isBlank()) {
            try { expectedAmt = new BigDecimal(amtStr); }
            catch (NumberFormatException ignored) {}
        }

        DepositVerificationService.DepositResult result =
            verificationService.verify(txHash, walletAddress, expectedAmt);

        // 입금 성공 시 해당 유저의 SSE 스트림에 이벤트 푸시
        if (result.isSuccess()) {
            notifyDepositSuccess(walletAddress, result.getAmount(), txHash);
            // 세션의 Member 객체도 갱신 (현재 페이지에서 잔액 바로 반영)
            member.setOmtBalance(result.getNewBalance());
        }

        return ResponseEntity.ok(result.toMap());
    }

    // =====================================================================
    // 3. SSE: 실시간 입금 감지 스트림
    //    프론트가 EventSource('/api/deposit/stream')로 구독
    //    서버 지갑으로 Transfer 이벤트 발생 시 해당 유저에게 푸시
    // =====================================================================

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter depositStream(HttpSession session) {
        Member member = getLoginMember(session);
        if (member == null || member.getWalletaddress() == null) {
            SseEmitter err = new SseEmitter(0L);
            err.complete();
            return err;
        }

        String userAddr = member.getWalletaddress().toLowerCase();
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L); // 5분 타임아웃

        emitter.onCompletion(() -> depositEmitters.remove(userAddr));
        emitter.onTimeout(()    -> depositEmitters.remove(userAddr));
        emitter.onError(e  -> depositEmitters.remove(userAddr));

        depositEmitters.put(userAddr, emitter);

        // 연결 확인 이벤트
        sendSseEvent(emitter, "connected", Map.of("message", "입금 감지 대기 중..."));

        // 아직 Web3j 리스너가 없다면 구독 시작 (최초 1회)
        startChainListener();

        return emitter;
    }

    // =====================================================================
    // private: 온체인 Transfer 이벤트 리스너 (실시간 감지)
    // =====================================================================

    private volatile boolean listenerStarted = false;

    private synchronized void startChainListener() {
        if (listenerStarted) return;

        Web3j web3j = web3jProvider.getIfAvailable();
        if (web3j == null || "none".equalsIgnoreCase(omtContractAddress)) return;

        String serverAddr;
        try {
            serverAddr = kmsSigner.isAvailable()
                ? kmsSigner.getEthereumAddress().toLowerCase()
                : null;
        } catch (Exception e) {
            log.warn("[Deposit] SSE 리스너 시작 실패 (KMS 주소 없음)");
            return;
        }
        if (serverAddr == null) return;

        EthFilter filter = new EthFilter(
            DefaultBlockParameter.valueOf("latest"),
            DefaultBlockParameter.valueOf("latest"),
            omtContractAddress
        );
        filter.addSingleTopic(TRANSFER_TOPIC);

        final String finalServerAddr = serverAddr;
        Executors.newSingleThreadExecutor().submit(() -> {
            web3j.ethLogFlowable(filter).subscribe(
                logEvent -> {
                    try {
                        var topics = logEvent.getTopics();
                        if (topics.size() < 3) return;

                        String toAddr   = decodeAddress(topics.get(2));
                        String fromAddr = decodeAddress(topics.get(1));

                        // 서버 지갑으로 들어오는 Transfer만 처리
                        if (!toAddr.equalsIgnoreCase(finalServerAddr)) return;

                        BigInteger rawAmt = Numeric.decodeQuantity(logEvent.getData());
                        BigDecimal amt    = new BigDecimal(rawAmt)
                            .divide(BigDecimal.TEN.pow(18), 6, java.math.RoundingMode.DOWN);

                        String txHash = logEvent.getTransactionHash();
                        log.info("[Deposit] 온체인 입금 감지: from={}, amount={} OMT, tx={}",
                            fromAddr, amt, txHash);

                        // 해당 유저 SSE에 알림
                        notifyDepositSuccess(fromAddr, amt, txHash);

                        // 자동 DB 업데이트 (SSE 감지 경로)
                        verificationService.verify(txHash, fromAddr, null);

                    } catch (Exception e) {
                        log.error("[Deposit] SSE 이벤트 처리 오류: {}", e.getMessage());
                    }
                },
                err -> log.error("[Deposit] SSE 리스너 오류: {}", err.getMessage())
            );
        });

        listenerStarted = true;
        log.info("[Deposit] 실시간 입금 감지 리스너 시작: contract={}", omtContractAddress);
    }

    private void notifyDepositSuccess(String userAddr, BigDecimal amount, String txHash) {
        SseEmitter emitter = depositEmitters.get(userAddr.toLowerCase());
        if (emitter == null) return;
        sendSseEvent(emitter, "deposit", Map.of(
            "amount",  amount.toPlainString(),
            "txHash",  txHash,
            "message", amount.toPlainString() + " OMT 입금이 감지됐어요!"
        ));
    }

    private void sendSseEvent(SseEmitter emitter, String eventName, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (Exception e) {
            emitter.complete();
        }
    }

    private String decodeAddress(String topic) {
        String hex = topic.replace("0x", "");
        if (hex.length() < 40) return "0x" + hex;
        return "0x" + hex.substring(hex.length() - 40);
    }

    private Member getLoginMember(HttpSession session) {
        return (Member) session.getAttribute("loginMember");
    }
}
