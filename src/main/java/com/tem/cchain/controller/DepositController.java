package com.tem.cchain.controller;

import com.tem.cchain.entity.Member;
import com.tem.cchain.repository.MemberRepository;
import com.tem.cchain.service.DepositVerificationService;
import com.tem.cchain.service.DepositVerificationService.TokenType;
import com.tem.cchain.wallet.kms.KmsTransactionSigner;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * 비수탁형 입금 API.
 *
 * [지갑 소유권 인증 — SIWE 패턴]
 *  GET  /api/deposit/wallet-challenge  → 서명 챌린지 메시지 발급
 *  POST /api/deposit/wallet-verify     → MetaMask personal_sign 서명 검증
 *
 * [입금 처리]
 *  GET  /api/deposit/server-address    → KMS 서버 지갑 주소
 *  POST /api/deposit/verify            → txHash 검증 + DB 업데이트 (OMT/USDT/ETH)
 *
 * [실시간 감지]
 *  GET  /api/deposit/stream (SSE)      → 온체인 Transfer 이벤트 실시간 푸시
 *
 * ---- 법적 컴플라이언스 ----
 * personal_sign 인증은 유저가 해당 지갑의 실제 소유자임을 증명합니다.
 * 서버는 프라이빗 키를 절대 보관하지 않으며(비수탁형),
 * 모든 서명·전송은 사용자의 MetaMask에서 직접 이루어집니다.
 * 이는 VASP(가상자산사업자) 규제 대상이 되는 "보관형 서비스"와 구별됩니다.
 */
@Slf4j
@RestController
@RequestMapping("/api/deposit")
@RequiredArgsConstructor
public class DepositController {

    private final DepositVerificationService verificationService;
    private final KmsTransactionSigner kmsSigner;
    private final MemberRepository memberRepository;
    private final ObjectProvider<Web3j> web3jProvider;

    private final Map<String, SseEmitter> depositEmitters = new ConcurrentHashMap<>();

    private static final String TRANSFER_TOPIC =
        "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

    @Value("${token.contract.address:none}")
    private String omtContractAddress;

    @Value("${wallet.deposit.usdt-contract:0x1c7d4b196cb0c7b01d743fbc6116a902379c7238}")
    private String usdtContractAddress;

    // =====================================================================
    // 1. 지갑 소유권 인증 — SIWE (Sign-In with Ethereum) 패턴
    // =====================================================================

    /**
     * 서명 챌린지 메시지를 생성합니다.
     * 세션에 nonce를 저장해 재사용 공격을 방지합니다.
     */
    @GetMapping("/wallet-challenge")
    public ResponseEntity<Map<String, Object>> walletChallenge(HttpSession session) {
        Member member = getLoginMember(session);
        if (member == null) return ResponseEntity.status(401).build();

        // 이미 인증된 경우
        if (Boolean.TRUE.equals(member.getWalletVerified())) {
            return ResponseEntity.ok(Map.of(
                "alreadyVerified", true,
                "message", "이미 지갑 소유권 인증이 완료된 계정입니다."
            ));
        }

        String nonce     = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String issuedAt  = Instant.now().toString();
        String walletAddr = member.getWalletaddress() != null ? member.getWalletaddress() : "(미연결)";

        // EIP-4361 SIWE 형식 — 사람이 읽을 수 있는 동의 메시지
        String challengeMsg = String.join("\n",
            "C-Chain이(가) 지갑 소유권 인증을 요청합니다.",
            "",
            "이 서명은 블록체인 트랜잭션을 발생시키지 않으며,",
            "가스비가 청구되지 않습니다.",
            "",
            "목적: C-Chain 플랫폼 입금 서비스 이용",
            "지갑: " + walletAddr,
            "Nonce: " + nonce,
            "발급 시각: " + issuedAt
        );

        session.setAttribute("depositNonce",   nonce);
        session.setAttribute("depositChallenge", challengeMsg);

        log.info("[Deposit] 챌린지 발급: user={}, nonce={}", member.getEmail(), nonce);

        return ResponseEntity.ok(Map.of(
            "alreadyVerified", false,
            "challenge", challengeMsg,
            "nonce",     nonce
        ));
    }

    /**
     * MetaMask personal_sign 서명을 검증합니다.
     * 서명에서 복구한 주소 == 등록된 지갑 주소 이면 인증 완료.
     */
    @PostMapping("/wallet-verify")
    @Transactional
    public ResponseEntity<Map<String, Object>> walletVerify(
            @RequestBody Map<String, String> body,
            HttpSession session) {

        Member member = getLoginMember(session);
        if (member == null) return ResponseEntity.status(401).build();

        String signature = body.get("signature");
        String address   = body.get("address");
        String challenge = (String) session.getAttribute("depositChallenge");

        if (signature == null || address == null || challenge == null)
            return ResponseEntity.badRequest()
                .body(Map.of("success", false, "message", "서명, 주소, 챌린지가 모두 필요합니다"));

        try {
            String recovered = recoverAddress(challenge, signature);

            if (!recovered.equalsIgnoreCase(address))
                return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", "서명 복구 주소 불일치: " + recovered + " ≠ " + address
                ));

            if (!address.equalsIgnoreCase(member.getWalletaddress()))
                return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", "인증된 주소가 계정에 등록된 지갑 주소와 다릅니다"
                ));

            // 인증 완료 — DB에 영구 저장
            member.setWalletVerified(true);
            memberRepository.save(member);
            session.removeAttribute("depositChallenge");
            session.removeAttribute("depositNonce");

            log.info("[Deposit] 지갑 소유권 인증 완료: user={}, addr={}", member.getEmail(), address);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "지갑 소유권 인증이 완료됐습니다. 이제 입금 서비스를 이용하실 수 있습니다."
            ));

        } catch (Exception e) {
            log.error("[Deposit] 서명 검증 오류: {}", e.getMessage());
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", "서명 검증 중 오류가 발생했습니다: " + e.getMessage()
            ));
        }
    }

    // =====================================================================
    // 2. 서버 지갑 주소 + 입금 정보 조회
    // =====================================================================

    @GetMapping("/server-address")
    public ResponseEntity<Map<String, Object>> serverAddress(HttpSession session) {
        Member member = getLoginMember(session);
        if (member == null) return ResponseEntity.status(401).build();

        String addr = "";
        boolean kmsOk = kmsSigner.isAvailable();
        try {
            if (kmsOk) addr = kmsSigner.getEthereumAddress();
        } catch (Exception e) {
            kmsOk = false;
            log.warn("[Deposit] KMS 주소 조회 실패: {}", e.getMessage());
        }

        return ResponseEntity.ok(Map.of(
            "serverAddress",   addr,
            "kmsAvailable",    kmsOk,
            "omtContract",     omtContractAddress,
            "usdtContract",    usdtContractAddress,
            "walletVerified",  Boolean.TRUE.equals(member.getWalletVerified()),
            "omtBalance",      orZero(member.getOmtBalance()),
            "usdtBalance",     orZero(member.getUsdtBalance()),
            "ethDepositBalance", orZero(member.getEthDepositBalance())
        ));
    }

    // =====================================================================
    // 3. txHash 검증 + DB 잔액 업데이트 (OMT / USDT / ETH)
    // =====================================================================

    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verify(
            @RequestBody Map<String, String> body,
            HttpSession session) {

        Member member = getLoginMember(session);
        if (member == null)
            return ResponseEntity.status(401).body(Map.of("message", "로그인이 필요합니다"));

        if (member.getWalletaddress() == null || member.getWalletaddress().isBlank())
            return ResponseEntity.badRequest()
                .body(Map.of("message", "MetaMask 지갑을 먼저 연결해 주세요"));

        String txHash    = body.get("txHash");
        String amtStr    = body.get("amount");
        String tokenStr  = body.getOrDefault("token", "OMT").toUpperCase();

        if (txHash == null || txHash.isBlank())
            return ResponseEntity.badRequest().body(Map.of("message", "txHash가 없습니다"));

        BigDecimal expectedAmt = null;
        try { if (amtStr != null) expectedAmt = new BigDecimal(amtStr); }
        catch (NumberFormatException ignored) {}

        TokenType tokenType;
        try { tokenType = TokenType.valueOf(tokenStr); }
        catch (IllegalArgumentException e) { tokenType = TokenType.OMT; }

        DepositVerificationService.DepositResult result =
            verificationService.verify(txHash, member.getWalletaddress(), expectedAmt, tokenType);

        if (result.isSuccess()) {
            notifyDepositSuccess(member.getWalletaddress(), result.getAmount(),
                                 txHash, tokenType.name());
            // 세션 Member 잔액 동기화
            switch (tokenType) {
                case OMT  -> member.setOmtBalance(result.getNewBalance());
                case USDT -> member.setUsdtBalance(result.getNewBalance());
                case ETH  -> member.setEthDepositBalance(result.getNewBalance());
            }
        }

        return ResponseEntity.ok(result.toMap());
    }

    // =====================================================================
    // 4. SSE: 실시간 입금 감지
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
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);
        emitter.onCompletion(() -> depositEmitters.remove(userAddr));
        emitter.onTimeout(()    -> depositEmitters.remove(userAddr));
        emitter.onError(e  -> depositEmitters.remove(userAddr));
        depositEmitters.put(userAddr, emitter);

        sendSseEvent(emitter, "connected", Map.of("message", "입금 감지 대기 중..."));
        startChainListener();
        return emitter;
    }

    // =====================================================================
    // private: 온체인 Transfer 이벤트 리스너
    // =====================================================================

    private volatile boolean listenerStarted = false;

    private synchronized void startChainListener() {
        if (listenerStarted) return;
        Web3j web3j = web3jProvider.getIfAvailable();
        if (web3j == null) return;

        String serverAddr;
        try {
            serverAddr = kmsSigner.isAvailable()
                ? kmsSigner.getEthereumAddress().toLowerCase() : null;
        } catch (Exception e) { return; }
        if (serverAddr == null) return;

        // OMT + USDT 컨트랙트 모두 구독
        java.util.List<String> contracts = new java.util.ArrayList<>();
        if (!"none".equalsIgnoreCase(omtContractAddress))  contracts.add(omtContractAddress);
        if (!"none".equalsIgnoreCase(usdtContractAddress)) contracts.add(usdtContractAddress);
        if (contracts.isEmpty()) return;

        EthFilter filter = new EthFilter(
            DefaultBlockParameter.valueOf("latest"),
            DefaultBlockParameter.valueOf("latest"),
            contracts
        );
        filter.addSingleTopic(TRANSFER_TOPIC);

        final String finalServerAddr = serverAddr;
        Executors.newSingleThreadExecutor().submit(() ->
            web3j.ethLogFlowable(filter).subscribe(logEvent -> {
                try {
                    var topics = logEvent.getTopics();
                    if (topics.size() < 3) return;
                    String to   = decodeAddress(topics.get(2));
                    String from = decodeAddress(topics.get(1));
                    if (!to.equalsIgnoreCase(finalServerAddr)) return;

                    BigInteger rawAmt = Numeric.decodeQuantity(logEvent.getData());

                    // 컨트랙트 주소로 토큰 타입 판별
                    boolean isUsdt = usdtContractAddress.equalsIgnoreCase(logEvent.getAddress());
                    int decimals   = isUsdt ? 6 : 18;
                    TokenType type = isUsdt ? TokenType.USDT : TokenType.OMT;

                    BigDecimal amt = new BigDecimal(rawAmt)
                        .divide(BigDecimal.TEN.pow(decimals), 6, java.math.RoundingMode.DOWN);

                    String txHash = logEvent.getTransactionHash();
                    log.info("[Deposit] 온체인 {} 입금 감지: from={}, amount={}, tx={}",
                        type, from, amt, txHash);

                    notifyDepositSuccess(from, amt, txHash, type.name());
                    verificationService.verify(txHash, from, null, type);

                } catch (Exception e) {
                    log.error("[Deposit] SSE 이벤트 처리 오류: {}", e.getMessage());
                }
            }, err -> log.error("[Deposit] SSE 리스너 오류: {}", err.getMessage()))
        );

        listenerStarted = true;
        log.info("[Deposit] 실시간 입금 감지 리스너 시작: {}", contracts);
    }

    // =====================================================================
    // private helpers
    // =====================================================================

    /**
     * MetaMask personal_sign 서명에서 주소를 복구합니다.
     * personal_sign은 메시지 앞에 "\x19Ethereum Signed Message:\n{length}" 를 붙입니다.
     */
    private String recoverAddress(String message, String signatureHex) throws Exception {
        byte[] msgBytes     = message.getBytes(StandardCharsets.UTF_8);
        String prefix       = "\u0019Ethereum Signed Message:\n" + msgBytes.length;
        byte[] prefixedHash = Hash.sha3(
            concat(prefix.getBytes(StandardCharsets.UTF_8), msgBytes)
        );

        byte[] sigBytes = Numeric.hexStringToByteArray(signatureHex);
        if (sigBytes.length != 65)
            throw new IllegalArgumentException("서명 길이 오류: " + sigBytes.length);

        byte v = sigBytes[64];
        if (v < 27) v += 27;  // MetaMask는 0/1로 반환하는 경우 있음

        Sign.SignatureData sig = new Sign.SignatureData(
            v,
            Arrays.copyOfRange(sigBytes, 0,  32),
            Arrays.copyOfRange(sigBytes, 32, 64)
        );

        BigInteger recoveredKey = Sign.signedMessageHashToKey(prefixedHash, sig);
        return "0x" + Keys.getAddress(recoveredKey);
    }

    private byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private void notifyDepositSuccess(String userAddr, BigDecimal amount,
                                      String txHash, String tokenType) {
        SseEmitter emitter = depositEmitters.get(userAddr.toLowerCase());
        if (emitter == null) return;
        sendSseEvent(emitter, "deposit", Map.of(
            "amount",    amount.toPlainString(),
            "tokenType", tokenType,
            "txHash",    txHash,
            "message",   amount.toPlainString() + " " + tokenType + " 입금이 감지됐어요!"
        ));
    }

    private void sendSseEvent(SseEmitter emitter, String name, Map<String, Object> data) {
        try { emitter.send(SseEmitter.event().name(name).data(data)); }
        catch (Exception e) { emitter.complete(); }
    }

    private String decodeAddress(String topic) {
        String hex = topic.replace("0x", "");
        return "0x" + (hex.length() >= 40 ? hex.substring(hex.length() - 40) : hex);
    }

    private String orZero(BigDecimal v) {
        return v != null ? v.toPlainString() : "0";
    }

    private Member getLoginMember(HttpSession session) {
        return (Member) session.getAttribute("loginMember");
    }
}
