package com.tem.cchain.wallet.security;

import com.tem.cchain.wallet.security.SecurityAuditLog.CheckResult;
import com.tem.cchain.wallet.security.SecurityAuditLog.CheckType;
import com.tem.cchain.wallet.security.fds.RealTimeFdsService;
import com.tem.cchain.wallet.security.fds.RealTimeFdsService.FdsResult;
import com.tem.cchain.wallet.security.fds.RealTimeFdsService.FdsVerdict;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * ════════════════════════════════════════════════════════════
 * [출금 보안 게이트] WithdrawalSecurityService
 * ════════════════════════════════════════════════════════════
 *
 * KMS 호출 직전에 반드시 통과해야 하는 보안 검문소입니다.
 * EnterpriseWalletService.signAndSendErc20Transfer() 내부에서
 * kmsSigner.signTransaction() 호출 직전에 실행됩니다.
 *
 * ── 검사 순서 (모든 결과는 securityAuditLog 테이블에 저장) ──
 *
 * [STEP 1] 빈도 제한 검사 (Rate Limit)
 *   - 동일 계정의 분당 출금 요청 횟수를 Redis에서 카운트
 *   - 분당 2회 초과 시 → FAIL (KMS 차단)
 *
 * [STEP 2] 고액 출금 임계값 검사 (High Amount)
 *   - 단건 출금액이 설정값(기본 10,000 OMT) 초과 시
 *   - → PENDING 상태 변경 + 운영자 알림 발송
 *   - 운영자 승인 전까지 KMS 호출 차단
 *
 * [STEP 3] 실시간 FDS 검사 (Fraud Detection)
 *   - RealTimeFdsService.evaluate() 호출
 *   - DENY  → 즉시 KMS 차단 (FdsBlockException throw)
 *   - PENDING → 승인 대기 + 운영자 알림
 *   - ALLOW → 다음 단계 진행
 *
 * [STEP 4] KMS 게이트 최종 로그 기록
 *   - 모든 검사 통과 시 → kmsCallAllowed=true 로 기록
 *   - 이 기록이 존재해야 KMS 서명이 허용됩니다
 *
 * ── 트랜잭션 원칙 ────────────────────────────────────────────
 * REQUIRES_NEW: 출금 트랜잭션과 독립적으로 감사 로그를 저장합니다.
 * 출금이 롤백되더라도 감사 기록은 반드시 DB에 남아야 합니다.
 * ════════════════════════════════════════════════════════════
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawalSecurityService {

    private final SecurityAuditLogRepository auditLogRepository;
    private final RealTimeFdsService fdsService;
    private final StringRedisTemplate redisTemplate;

    // ── 설정값 ───────────────────────────────────────────────

    /** 빈도 제한: 분당 허용 출금 요청 횟수 */
    @Value("${wallet.security.rate-limit.per-minute:2}")
    private int rateLimitPerMinute;

    /** 고액 출금 임계값 (OMT 단위). 이 금액 초과 시 운영자 승인 필요 */
    @Value("${wallet.security.high-amount-threshold:10000}")
    private BigDecimal highAmountThreshold;

    // Redis 키 접두사 (분당 출금 요청 카운터)
    private static final String RATE_KEY_PREFIX = "withdrawal:rate:";

    // =========================================================
    // 공개 메서드: KMS 직전 보안 게이트 진입점
    // =========================================================

    /**
     * 출금 요청이 KMS 서명을 받을 자격이 있는지 모든 보안 검사를 수행합니다.
     *
     * ❗ 반드시 kmsSigner.signTransaction() 호출 직전에 실행하세요.
     *    이 메서드가 예외를 throw하면 KMS 호출 코드에 도달하지 않습니다.
     *
     * @param callerEmail 출금 요청자 이메일 (Spring Security 세션)
     * @param toAddress   출금 대상 주소
     * @param amount      출금 금액 (토큰 단위, Wei 아님)
     * @param tokenSymbol 토큰 종류 (OMT, ETH 등)
     * @param ipAddress   요청자 IP 주소
     * @throws WithdrawalBlockedException 검사 실패 시 (KMS 호출 차단)
     * @throws WithdrawalPendingException 승인 대기 상태로 전환 시
     */
    public void enforceSecurityGate(String callerEmail, String toAddress,
                                     BigDecimal amount, String tokenSymbol,
                                     String ipAddress) {

        // 요청 상관 ID 생성 (이 출금 요청의 모든 검사 로그를 묶는 키)
        String requestId = UUID.randomUUID().toString();
        MDC.put("requestId", requestId); // 로그에 requestId 자동 포함

        log.info("[SecurityGate] 보안 검사 시작: requestId={}, caller={}, amount={} {}",
                requestId, callerEmail, amount, tokenSymbol);

        try {
            // ── STEP 1: 빈도 제한 검사 ──────────────────────
            enforceRateLimit(requestId, callerEmail, toAddress, amount, tokenSymbol, ipAddress);

            // ── STEP 2: 고액 출금 검사 ──────────────────────
            enforceHighAmountCheck(requestId, callerEmail, toAddress, amount, tokenSymbol, ipAddress);

            // ── STEP 3: 실시간 FDS 검사 ─────────────────────
            enforceFdsCheck(requestId, callerEmail, toAddress, amount, tokenSymbol, ipAddress);

            // ── STEP 4: 모든 검사 통과 → KMS 게이트 PASS 기록
            recordKmsGatePass(requestId, callerEmail, toAddress, amount, tokenSymbol, ipAddress);

            log.info("[SecurityGate] 모든 검사 통과. KMS 서명 허용: requestId={}", requestId);

        } finally {
            MDC.remove("requestId");
        }
    }

    // =========================================================
    // STEP 1: 빈도 제한 검사
    // =========================================================

    /**
     * Redis에서 분당 출금 요청 횟수를 카운트합니다.
     * rateLimitPerMinute 초과 시 감사 로그 저장 후 예외를 throw합니다.
     *
     * Redis 키: withdrawal:rate:{callerEmail}
     * TTL: 60초 (1분 슬라이딩 윈도우)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enforceRateLimit(String requestId, String callerEmail,
                                  String toAddress, BigDecimal amount,
                                  String tokenSymbol, String ipAddress) {
        String key = RATE_KEY_PREFIX + callerEmail;
        int currentCount = 0;

        try {
            String countStr = redisTemplate.opsForValue().get(key);
            currentCount = (countStr == null) ? 0 : Integer.parseInt(countStr);

            // 카운트 증가
            redisTemplate.opsForValue().increment(key);
            if (currentCount == 0) {
                // 최초 요청: 1분 TTL 설정
                redisTemplate.expire(key, Duration.ofMinutes(1));
            }
        } catch (Exception e) {
            // Redis 장애 시: 보수적으로 허용 (서비스 중단 방지)
            log.error("[SecurityGate][RateLimit] Redis 장애, 우회 허용: {}", e.getMessage());
            saveAuditLog(requestId, callerEmail, ipAddress, toAddress, tokenSymbol, amount,
                    CheckType.RATE_LIMIT, CheckResult.PASS, -1,
                    "Redis 장애로 우회 허용", false, false);
            return;
        }

        if (currentCount >= rateLimitPerMinute) {
            // 빈도 제한 초과 → KMS 차단
            String reason = String.format("분당 출금 횟수 %d회 초과 (현재 %d회)",
                    rateLimitPerMinute, currentCount + 1);

            saveAuditLog(requestId, callerEmail, ipAddress, toAddress, tokenSymbol, amount,
                    CheckType.RATE_LIMIT, CheckResult.FAIL, -1,
                    reason, false, false);

            log.warn("[SecurityGate][RateLimit] 차단: caller={}, reason={}", callerEmail, reason);
            throw new WithdrawalBlockedException("빈도 제한 초과: " + reason);
        }

        // 통과
        saveAuditLog(requestId, callerEmail, ipAddress, toAddress, tokenSymbol, amount,
                CheckType.RATE_LIMIT, CheckResult.PASS, -1, null, false, false);
    }

    // =========================================================
    // STEP 2: 고액 출금 임계값 검사
    // =========================================================

    /**
     * 출금액이 highAmountThreshold를 초과하면 PENDING 상태로 전환합니다.
     * 운영자에게 알림을 발송하고 예외를 throw하여 KMS 호출을 차단합니다.
     *
     * 운영자 승인 후에는 별도 승인 API를 통해 처리됩니다.
     * (TODO: 운영자 승인 → PendingWithdrawalService 구현 예정)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enforceHighAmountCheck(String requestId, String callerEmail,
                                        String toAddress, BigDecimal amount,
                                        String tokenSymbol, String ipAddress) {
        if (amount.compareTo(highAmountThreshold) <= 0) {
            // 임계값 이하 → 통과
            saveAuditLog(requestId, callerEmail, ipAddress, toAddress, tokenSymbol, amount,
                    CheckType.HIGH_AMOUNT, CheckResult.PASS, -1, null, false, false);
            return;
        }

        // 고액 출금 감지 → PENDING + 운영자 알림
        String reason = String.format("고액 출금 임계값 초과 (요청 %s %s, 임계 %s %s)",
                amount.toPlainString(), tokenSymbol,
                highAmountThreshold.toPlainString(), tokenSymbol);

        // 운영자 알림 발송 (현재는 로그 + DB 기록, 향후 Slack/Email 연동)
        notifyOperator(callerEmail, toAddress, amount, tokenSymbol, reason);

        saveAuditLog(requestId, callerEmail, ipAddress, toAddress, tokenSymbol, amount,
                CheckType.HIGH_AMOUNT, CheckResult.PENDING, -1,
                reason, true, false);

        // 운영자 알림 발송 기록도 별도 행으로 저장
        saveAuditLog(requestId, callerEmail, ipAddress, toAddress, tokenSymbol, amount,
                CheckType.OPERATOR_NOTIFY, CheckResult.PASS, -1,
                "운영자 알림 발송 완료", true, false);

        log.warn("[SecurityGate][HighAmount] PENDING: caller={}, amount={} {}, requestId={}",
                callerEmail, amount, tokenSymbol, requestId);

        throw new WithdrawalPendingException(
                "고액 출금 승인 대기 중입니다. 운영자 승인 후 처리됩니다. (requestId=" + requestId + ")");
    }

    // =========================================================
    // STEP 3: 실시간 FDS 검사
    // =========================================================

    /**
     * RealTimeFdsService에 위임하여 이상거래 탐지를 수행합니다.
     * FDS 판정에 따라 DENY(차단) 또는 PENDING(대기)으로 분기합니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enforceFdsCheck(String requestId, String callerEmail,
                                 String toAddress, BigDecimal amount,
                                 String tokenSymbol, String ipAddress) {

        FdsResult fdsResult = fdsService.evaluate(callerEmail, toAddress, amount, ipAddress);

        if (fdsResult.getVerdict() == FdsVerdict.DENY) {
            // FDS DENY → 즉시 물리적 차단
            saveAuditLog(requestId, callerEmail, ipAddress, toAddress, tokenSymbol, amount,
                    CheckType.FDS, CheckResult.FAIL,
                    fdsResult.getRiskScore(), fdsResult.getReason(), false, false);

            log.error("[SecurityGate][FDS] DENY 차단: caller={}, score={}, reason={}",
                    callerEmail, fdsResult.getRiskScore(), fdsResult.getReason());

            throw new RealTimeFdsService.FdsBlockException(
                    fdsResult.getReason(), fdsResult.getRiskScore());

        } else if (fdsResult.getVerdict() == FdsVerdict.PENDING) {
            // FDS PENDING → 운영자 검토 요청
            notifyOperator(callerEmail, toAddress, amount, tokenSymbol,
                    "[FDS 의심 거래] " + fdsResult.getReason());

            saveAuditLog(requestId, callerEmail, ipAddress, toAddress, tokenSymbol, amount,
                    CheckType.FDS, CheckResult.PENDING,
                    fdsResult.getRiskScore(), fdsResult.getReason(), true, false);

            log.warn("[SecurityGate][FDS] PENDING: caller={}, score={}", callerEmail, fdsResult.getRiskScore());

            throw new WithdrawalPendingException(
                    "FDS 의심 거래 감지. 운영자 검토 후 처리됩니다. (score=" + fdsResult.getRiskScore() + ")");

        } else {
            // FDS ALLOW → 통과
            saveAuditLog(requestId, callerEmail, ipAddress, toAddress, tokenSymbol, amount,
                    CheckType.FDS, CheckResult.PASS,
                    fdsResult.getRiskScore(), null, false, false);
        }
    }

    // =========================================================
    // STEP 4: KMS 게이트 최종 통과 기록
    // =========================================================

    /**
     * 모든 검사를 통과한 경우 KMS_GATE PASS 로그를 저장합니다.
     * kmsCallAllowed=true 로 기록하여 감사 추적성을 확보합니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordKmsGatePass(String requestId, String callerEmail,
                                   String toAddress, BigDecimal amount,
                                   String tokenSymbol, String ipAddress) {
        saveAuditLog(requestId, callerEmail, ipAddress, toAddress, tokenSymbol, amount,
                CheckType.KMS_GATE, CheckResult.PASS, 0,
                "모든 보안 검사 통과 → KMS 서명 허용", false, true);
    }

    // =========================================================
    // 운영자 알림 (현재: 로그 기반, 향후 Slack/Email 연동)
    // =========================================================

    /**
     * 운영자에게 고액 출금 또는 이상거래 알림을 발송합니다.
     *
     * 현재 구현: 로그 출력 + DB 기록
     * 향후 확장: Slack Webhook, Email (JavaMailSender), PagerDuty 연동
     *
     * @param callerEmail 요청자 이메일
     * @param toAddress   출금 대상 주소
     * @param amount      출금 금액
     * @param tokenSymbol 토큰 종류
     * @param reason      알림 사유
     */
    private void notifyOperator(String callerEmail, String toAddress,
                                 BigDecimal amount, String tokenSymbol, String reason) {
        // 현재: 경고 로그로 운영자 알림 대체
        log.warn("""
                ╔══════════════════════════════════════════════════╗
                ║          [운영자 알림] 출금 검토 필요            ║
                ╠══════════════════════════════════════════════════╣
                ║ 요청자  : {}
                ║ 대상    : {}
                ║ 금액    : {} {}
                ║ 사유    : {}
                ╚══════════════════════════════════════════════════╝
                """, callerEmail, toAddress, amount, tokenSymbol, reason);

        // TODO: Slack Webhook 연동 예시
        // slackNotifier.send("#security-alerts", "[출금 검토 필요] " + reason);

        // TODO: Email 연동 예시
        // mailSender.sendOperatorAlert(callerEmail, toAddress, amount, tokenSymbol, reason);
    }

    // =========================================================
    // 감사 로그 저장 (REQUIRES_NEW 독립 트랜잭션)
    // =========================================================

    /**
     * 보안 검사 결과를 security_audit_log 테이블에 저장합니다.
     * REQUIRES_NEW 전파 설정으로 출금 트랜잭션과 독립적으로 커밋됩니다.
     * → 출금 실패/롤백과 무관하게 감사 기록이 반드시 남습니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAuditLog(String requestId, String callerEmail, String ipAddress,
                              String toAddress, String tokenSymbol, BigDecimal amount,
                              CheckType checkType, CheckResult checkResult,
                              int riskScore, String blockReason,
                              boolean operatorNotified, boolean kmsCallAllowed) {
        SecurityAuditLog log = SecurityAuditLog.builder()
                .requestId(requestId)
                .callerEmail(callerEmail)
                .ipAddress(ipAddress)
                .toAddress(toAddress)
                .tokenSymbol(tokenSymbol)
                .amount(amount)
                .checkType(checkType)
                .checkResult(checkResult)
                .riskScore(riskScore)
                .blockReason(blockReason)
                .operatorNotified(operatorNotified)
                .kmsCallAllowed(kmsCallAllowed)
                .build();

        auditLogRepository.save(log);
    }

    // =========================================================
    // 예외 클래스
    // =========================================================

    /** 보안 검사 실패로 출금이 즉시 차단된 경우 */
    public static class WithdrawalBlockedException extends RuntimeException {
        public WithdrawalBlockedException(String message) {
            super("출금 차단: " + message);
        }
    }

    /** 운영자 승인 대기 상태로 전환된 경우 */
    public static class WithdrawalPendingException extends RuntimeException {
        public WithdrawalPendingException(String message) {
            super("출금 승인 대기: " + message);
        }
    }
}
