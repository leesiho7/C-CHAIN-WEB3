package com.tem.cchain.wallet.security.fds;

import com.tem.cchain.wallet.security.SecurityAuditLog;
import com.tem.cchain.wallet.security.SecurityAuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * ════════════════════════════════════════════════════════════
 * [실시간 이상거래 탐지 시스템] RealTimeFdsService
 * ════════════════════════════════════════════════════════════
 *
 * 커스터디 서버에서 출금 직전에 실행되는 FDS입니다.
 * WithdrawalSecurityService 가 KMS 호출 직전에 이 서비스를
 * 호출하며, DENY 결과가 반환되면 KMS 서명이 물리적으로 차단됩니다.
 *
 * ── 탐지 규칙 (우선순위 순) ─────────────────────────────────
 *
 * [RULE-F01] 단시간 고빈도 출금 (Velocity Check)
 *   - 동일 계정에서 10분 내 3회 이상 출금 시도 → 위험 +40점
 *
 * [RULE-F02] 비정상 시간대 고액 출금 (Time-based Anomaly)
 *   - 새벽 0~5시(KST) + 출금액 > 평균 3배 → 위험 +30점
 *
 * [RULE-F03] 동일 대상 주소 반복 출금 (Destination Anomaly)
 *   - 1시간 내 동일 주소로 3회 이상 → 위험 +20점
 *
 * [RULE-F04] 과거 차단 이력 보유 계정 (Reputation Check)
 *   - 24시간 내 FAIL 이력 2회 이상 → 위험 +30점
 *
 * ── 판정 기준 ───────────────────────────────────────────────
 * riskScore  0 ~ 39  → ALLOW  (정상)
 * riskScore 40 ~ 69  → PENDING (운영자 검토 요청)
 * riskScore 70 ~ 100 → DENY   (즉시 물리적 차단)
 *
 * ── 중요 ────────────────────────────────────────────────────
 * 이 서비스의 DENY 결과는 예외(FdsBlockException)를 throw하여
 * KMS 서명 코드에 절대 도달하지 못하도록 합니다.
 * ════════════════════════════════════════════════════════════
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RealTimeFdsService {

    private final StringRedisTemplate redisTemplate;
    private final SecurityAuditLogRepository auditLogRepository;

    // ── 설정값 ───────────────────────────────────────────────

    /** 단시간 고빈도 탐지: 10분 내 허용 출금 횟수 (초과 시 Velocity 위험 판정) */
    @Value("${wallet.fds.velocity.max-count:3}")
    private int velocityMaxCount;

    /** 단시간 고빈도 탐지: 탐지 시간 윈도우 (분) */
    @Value("${wallet.fds.velocity.window-minutes:10}")
    private int velocityWindowMinutes;

    /** FDS DENY 임계 점수 */
    @Value("${wallet.fds.score.deny-threshold:70}")
    private int denyThreshold;

    /** FDS PENDING 임계 점수 */
    @Value("${wallet.fds.score.pending-threshold:40}")
    private int pendingThreshold;

    // Redis 키 접두사 (출금 속도 카운터)
    private static final String VELOCITY_KEY_PREFIX = "fds:velocity:";

    // =========================================================
    // 공개 메서드: FDS 평가 진입점
    // =========================================================

    /**
     * 출금 요청에 대한 실시간 FDS 평가를 수행합니다.
     *
     * WithdrawalSecurityService 에서 KMS 호출 직전에 반드시 호출해야 합니다.
     *
     * @param callerEmail 출금 요청자 이메일
     * @param toAddress   출금 대상 주소
     * @param amount      출금 금액 (토큰 단위)
     * @param ipAddress   요청자 IP
     * @return FdsResult  위험 점수 및 판정 결과 포함
     */
    public FdsResult evaluate(String callerEmail, String toAddress,
                               BigDecimal amount, String ipAddress) {

        log.info("[FDS] 평가 시작: caller={}, to={}, amount={}", callerEmail, toAddress, amount);

        int totalRiskScore = 0;
        StringBuilder reasons = new StringBuilder();

        // ── RULE-F01: 단시간 고빈도 출금 탐지 ───────────────
        int velocityScore = checkVelocity(callerEmail);
        if (velocityScore > 0) {
            totalRiskScore += velocityScore;
            reasons.append("[F01] ").append(velocityWindowMinutes)
                   .append("분 내 고빈도 출금 감지 (+").append(velocityScore).append("점); ");
        }

        // ── RULE-F02: 비정상 시간대 고액 출금 탐지 ──────────
        int timeScore = checkTimeAnomaly(amount);
        if (timeScore > 0) {
            totalRiskScore += timeScore;
            reasons.append("[F02] 새벽 시간대 고액 출금 감지 (+").append(timeScore).append("점); ");
        }

        // ── RULE-F03: 동일 대상 주소 반복 출금 탐지 ─────────
        int destinationScore = checkDestinationAnomaly(toAddress);
        if (destinationScore > 0) {
            totalRiskScore += destinationScore;
            reasons.append("[F03] 동일 주소 반복 출금 감지 (+").append(destinationScore).append("점); ");
        }

        // ── RULE-F04: 과거 차단 이력 보유 계정 탐지 ─────────
        int reputationScore = checkReputation(callerEmail);
        if (reputationScore > 0) {
            totalRiskScore += reputationScore;
            reasons.append("[F04] 최근 차단 이력 보유 (+").append(reputationScore).append("점); ");
        }

        // 최대 100점으로 제한
        totalRiskScore = Math.min(totalRiskScore, 100);

        // ── 판정 ─────────────────────────────────────────────
        FdsVerdict verdict;
        if (totalRiskScore >= denyThreshold) {
            verdict = FdsVerdict.DENY;
            log.warn("[FDS] DENY 판정: caller={}, score={}, reasons={}", callerEmail, totalRiskScore, reasons);
        } else if (totalRiskScore >= pendingThreshold) {
            verdict = FdsVerdict.PENDING;
            log.warn("[FDS] PENDING 판정: caller={}, score={}, reasons={}", callerEmail, totalRiskScore, reasons);
        } else {
            verdict = FdsVerdict.ALLOW;
            log.info("[FDS] ALLOW 판정: caller={}, score={}", callerEmail, totalRiskScore);
        }

        return FdsResult.builder()
                .verdict(verdict)
                .riskScore(totalRiskScore)
                .reason(reasons.toString().trim())
                .build();
    }

    // =========================================================
    // RULE-F01: 단시간 고빈도 출금 (Velocity Check)
    // =========================================================

    /**
     * Redis에서 슬라이딩 윈도우 방식으로 출금 횟수를 카운트합니다.
     * 임계값 초과 시 40점 반환.
     *
     * Redis 키: fds:velocity:{callerEmail}
     * TTL: velocityWindowMinutes (분)
     */
    private int checkVelocity(String callerEmail) {
        String key = VELOCITY_KEY_PREFIX + callerEmail;
        try {
            // 현재 카운트 조회
            String countStr = redisTemplate.opsForValue().get(key);
            int count = (countStr == null) ? 0 : Integer.parseInt(countStr);

            // 카운트 증가 + TTL 설정 (최초 요청 시)
            redisTemplate.opsForValue().increment(key);
            if (count == 0) {
                redisTemplate.expire(key,
                    java.time.Duration.ofMinutes(velocityWindowMinutes));
            }

            if (count >= velocityMaxCount) {
                return 40; // 위험 점수 +40
            }
        } catch (Exception e) {
            // Redis 장애 시: 보수적으로 위험 점수 부여
            log.error("[FDS][F01] Redis 조회 실패, 보수적 판정 적용: {}", e.getMessage());
            return 20;
        }
        return 0;
    }

    // =========================================================
    // RULE-F02: 비정상 시간대 고액 출금 탐지
    // =========================================================

    /**
     * KST 기준 새벽 0~5시에 고액 출금 시도 시 위험 점수를 부여합니다.
     * 여기서 "고액" 기준은 50,000 OMT (설정값으로 분리 가능).
     */
    private int checkTimeAnomaly(BigDecimal amount) {
        // KST = UTC+9
        int hourKst = (LocalDateTime.now().getHour() + 9) % 24;
        boolean isOddHour = hourKst >= 0 && hourKst < 5;
        boolean isLargeAmount = amount.compareTo(new BigDecimal("50000")) >= 0;

        if (isOddHour && isLargeAmount) {
            return 30; // 위험 점수 +30
        }
        return 0;
    }

    // =========================================================
    // RULE-F03: 동일 대상 주소 반복 출금 탐지
    // =========================================================

    /**
     * 1시간 내 동일 주소로 3회 이상 출금 시도 시 위험 점수를 부여합니다.
     * security_audit_log 테이블에서 최근 이력을 조회합니다.
     */
    private int checkDestinationAnomaly(String toAddress) {
        try {
            LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
            List<SecurityAuditLog> recent =
                auditLogRepository.findRecentTransfersByToAddress(toAddress, oneHourAgo);

            if (recent.size() >= 3) {
                return 20; // 위험 점수 +20
            }
        } catch (Exception e) {
            log.error("[FDS][F03] 대상 주소 이력 조회 실패: {}", e.getMessage());
        }
        return 0;
    }

    // =========================================================
    // RULE-F04: 과거 차단 이력 기반 평판 검사
    // =========================================================

    /**
     * 24시간 내 동일 계정의 FAIL 이력이 2회 이상이면 위험 점수를 부여합니다.
     * 반복 시도 패턴의 공격자를 사전에 차단합니다.
     */
    private int checkReputation(String callerEmail) {
        try {
            LocalDateTime yesterday = LocalDateTime.now().minusHours(24);
            long failCount = auditLogRepository.countFailedAttemptsSince(callerEmail, yesterday);

            if (failCount >= 2) {
                return 30; // 위험 점수 +30
            }
        } catch (Exception e) {
            log.error("[FDS][F04] 차단 이력 조회 실패: {}", e.getMessage());
        }
        return 0;
    }

    // =========================================================
    // 결과 DTO
    // =========================================================

    /** FDS 판정 결과 열거형 */
    public enum FdsVerdict {
        ALLOW,   // 정상 → KMS 진행
        PENDING, // 의심 → 운영자 검토 대기
        DENY     // 이상거래 → KMS 물리적 차단
    }

    /** FDS 평가 결과 DTO */
    @lombok.Builder
    @lombok.Getter
    public static class FdsResult {
        private final FdsVerdict verdict;  // 판정 결과
        private final int riskScore;       // 종합 위험 점수 (0~100)
        private final String reason;       // 판정 사유 (복수 규칙 누적)

        public boolean isBlocked() {
            return verdict == FdsVerdict.DENY;
        }

        public boolean isPending() {
            return verdict == FdsVerdict.PENDING;
        }
    }

    /**
     * FDS가 출금을 물리적으로 차단할 때 throw하는 예외.
     * 이 예외가 throw되면 호출 스택 어디에도 KMS 서명 코드가 실행되지 않습니다.
     */
    public static class FdsBlockException extends RuntimeException {
        private final int riskScore;

        public FdsBlockException(String reason, int riskScore) {
            super("FDS 차단: " + reason + " (위험점수=" + riskScore + ")");
            this.riskScore = riskScore;
        }

        public int getRiskScore() {
            return riskScore;
        }
    }
}
