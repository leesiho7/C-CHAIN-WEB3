package com.tem.cchain.wallet.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ════════════════════════════════════════════════════════════
 * [개발/데모용 보안 감사 로그 시드 데이터 생성기]
 * ════════════════════════════════════════════════════════════
 *
 * 보안 관제 센터 UI 시연을 위해 현실적인 샘플 로그를 주입합니다.
 * DB에 이미 데이터가 있으면 중복 삽입을 건너뜁니다 (멱등성 보장).
 *
 * ── 시나리오 ───────────────────────────────────────────────
 *  SCENARIO-A : 정상 소액 출금 3건 (PASS)
 *  SCENARIO-B : 빈도 제한 초과 차단 (RATE_LIMIT FAIL)
 *  SCENARIO-C : 고액 출금 → 운영자 승인 대기 (HIGH_AMOUNT PENDING)
 *  SCENARIO-D : FDS 고위험 탐지 → 즉시 차단 (FDS riskScore 87)
 *  SCENARIO-E : 블랙리스트 주소 출금 시도 차단 (BLACKLIST FAIL)
 *  SCENARIO-F : FDS 중위험 → 승인 대기 (FDS riskScore 55)
 *  SCENARIO-G : 복합 이상 — RATE_LIMIT 통과 후 FDS 고위험 (riskScore 93)
 * ════════════════════════════════════════════════════════════
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityAuditLogSeeder implements CommandLineRunner {

    private final SecurityAuditLogRepository repository;

    @Override
    public void run(String... args) {
        try {
            seedIfEmpty();
        } catch (Exception e) {
            log.warn("[SecuritySeeder] 시드 삽입 실패 — 앱 시작은 계속됩니다: {}", e.getMessage());
        }
    }

    private void seedIfEmpty() {
        if (repository.count() > 0) {
            log.info("[SecuritySeeder] 기존 감사 로그 존재 — 시드 건너뜀 ({} 건)", repository.count());
            return;
        }

        List<SecurityAuditLog> seeds = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        // ── SCENARIO-A : 정상 소액 출금 3건 ─────────────────────
        for (int i = 0; i < 3; i++) {
            String rid = UUID.randomUUID().toString();
            String user = "user0" + (i + 1) + "@cchain.com";
            String from = "0xA" + String.format("%039d", i + 1);
            String to   = "0xB" + String.format("%039d", i + 100);
            LocalDateTime ts = now.minusHours(1 + i * 2);

            // RATE_LIMIT PASS
            seeds.add(buildLog(rid, user, from, to, "OMT",
                    BigDecimal.valueOf(120 + i * 30),
                    SecurityAuditLog.CheckType.RATE_LIMIT,
                    SecurityAuditLog.CheckResult.PASS,
                    -1, null, false, false, ts));

            // FDS PASS
            seeds.add(buildLog(rid, user, from, to, "OMT",
                    BigDecimal.valueOf(120 + i * 30),
                    SecurityAuditLog.CheckType.FDS,
                    SecurityAuditLog.CheckResult.PASS,
                    12 + i * 5, null, false, false, ts.plusSeconds(1)));

            // KMS_GATE PASS
            seeds.add(buildLog(rid, user, from, to, "OMT",
                    BigDecimal.valueOf(120 + i * 30),
                    SecurityAuditLog.CheckType.KMS_GATE,
                    SecurityAuditLog.CheckResult.PASS,
                    -1, null, true, false, ts.plusSeconds(2)));
        }

        // ── SCENARIO-B : 빈도 제한 초과 차단 ────────────────────
        {
            String rid  = UUID.randomUUID().toString();
            String user = "spammer@external.io";
            String from = "0xDEAD" + "0".repeat(36);
            String to   = "0xF001" + "0".repeat(36);
            LocalDateTime ts = now.minusMinutes(45);

            seeds.add(buildLog(rid, user, from, to, "OMT",
                    BigDecimal.valueOf(500),
                    SecurityAuditLog.CheckType.RATE_LIMIT,
                    SecurityAuditLog.CheckResult.FAIL,
                    -1,
                    "[RATE] 분당 출금 횟수 한도 초과 (요청 8회 / 허용 5회)",
                    false, false, ts));
        }

        // ── SCENARIO-C : 고액 출금 → 운영자 승인 대기 ───────────
        {
            String rid  = UUID.randomUUID().toString();
            String user = "whale@cchain.com";
            String from = "0xC001" + "0".repeat(36);
            String to   = "0xC002" + "0".repeat(36);
            LocalDateTime ts = now.minusMinutes(30);

            seeds.add(buildLog(rid, user, from, to, "OMT",
                    BigDecimal.valueOf(85000),
                    SecurityAuditLog.CheckType.RATE_LIMIT,
                    SecurityAuditLog.CheckResult.PASS,
                    -1, null, false, false, ts));

            seeds.add(buildLog(rid, user, from, to, "OMT",
                    BigDecimal.valueOf(85000),
                    SecurityAuditLog.CheckType.HIGH_AMOUNT,
                    SecurityAuditLog.CheckResult.PENDING,
                    -1,
                    "[HIGH] 고액 출금 임계값 초과 — 운영자 승인 대기 (요청 85,000 OMT / 임계 10,000 OMT)",
                    false, true, ts.plusSeconds(1)));

            seeds.add(buildLog(rid, user, from, to, "OMT",
                    BigDecimal.valueOf(85000),
                    SecurityAuditLog.CheckType.OPERATOR_NOTIFY,
                    SecurityAuditLog.CheckResult.PASS,
                    -1, "운영자 알림 발송 완료 (Slack #wallet-alert)", false, true, ts.plusSeconds(2)));
        }

        // ── SCENARIO-D : FDS 고위험 탐지 즉시 차단 ─────────────
        {
            String rid  = UUID.randomUUID().toString();
            String user = "suspicious@mail.ru";
            String from = "0xBAD0" + "0".repeat(36);
            String to   = "0xBAD1" + "0".repeat(36);
            LocalDateTime ts = now.minusMinutes(20);

            seeds.add(buildLog(rid, user, from, to, "USDT",
                    BigDecimal.valueOf(3200),
                    SecurityAuditLog.CheckType.RATE_LIMIT,
                    SecurityAuditLog.CheckResult.PASS,
                    -1, null, false, false, ts));

            seeds.add(buildLog(rid, user, from, to, "USDT",
                    BigDecimal.valueOf(3200),
                    SecurityAuditLog.CheckType.FDS,
                    SecurityAuditLog.CheckResult.FAIL,
                    87,
                    "[F01] 단시간 고빈도 출금 (+40) [F02] 심야 시간대 이상 거래 (+30) [F04] 단기 다실패 이력 (+17)",
                    false, true, ts.plusSeconds(1)));
        }

        // ── SCENARIO-E : 블랙리스트 주소 출금 시도 ──────────────
        {
            String rid  = UUID.randomUUID().toString();
            String user = "user99@cchain.com";
            String from = "0xE001" + "0".repeat(36);
            String to   = "0x000000000000000000000000000000000bad1ead";
            LocalDateTime ts = now.minusMinutes(12);

            seeds.add(buildLog(rid, user, from, to, "OMT",
                    BigDecimal.valueOf(999),
                    SecurityAuditLog.CheckType.BLACKLIST,
                    SecurityAuditLog.CheckResult.FAIL,
                    -1,
                    "[BLACKLIST] 출금 대상 주소가 제재 블랙리스트에 등록된 주소입니다 (OFAC SDN 목록 일치)",
                    false, false, ts));
        }

        // ── SCENARIO-F : FDS 중위험 → 승인 대기 ─────────────────
        {
            String rid  = UUID.randomUUID().toString();
            String user = "medium@cchain.com";
            String from = "0xF101" + "0".repeat(36);
            String to   = "0xF102" + "0".repeat(36);
            LocalDateTime ts = now.minusMinutes(7);

            seeds.add(buildLog(rid, user, from, to, "ETH",
                    BigDecimal.valueOf(2.5),
                    SecurityAuditLog.CheckType.RATE_LIMIT,
                    SecurityAuditLog.CheckResult.PASS,
                    -1, null, false, false, ts));

            seeds.add(buildLog(rid, user, from, to, "ETH",
                    BigDecimal.valueOf(2.5),
                    SecurityAuditLog.CheckType.FDS,
                    SecurityAuditLog.CheckResult.PENDING,
                    55,
                    "[F03] 동일 주소 반복 출금 패턴 감지 (+20) [F02] 비정상 시간대 요청 (+30) [F01] 속도 이상 (+5)",
                    false, true, ts.plusSeconds(1)));
        }

        // ── SCENARIO-G : 복합 이상 — 최고 위험도 ────────────────
        {
            String rid  = UUID.randomUUID().toString();
            String user = "attacker@anon.xyz";
            String from = "0xDEAD" + "1".repeat(36);
            String to   = "0xCAFE" + "B".repeat(36);
            LocalDateTime ts = now.minusMinutes(3);

            seeds.add(buildLog(rid, user, from, to, "OMT",
                    BigDecimal.valueOf(47500),
                    SecurityAuditLog.CheckType.RATE_LIMIT,
                    SecurityAuditLog.CheckResult.PASS,
                    -1, null, false, false, ts));

            seeds.add(buildLog(rid, user, from, to, "OMT",
                    BigDecimal.valueOf(47500),
                    SecurityAuditLog.CheckType.HIGH_AMOUNT,
                    SecurityAuditLog.CheckResult.PASS,
                    -1, null, false, true, ts.plusSeconds(1)));

            seeds.add(buildLog(rid, user, from, to, "OMT",
                    BigDecimal.valueOf(47500),
                    SecurityAuditLog.CheckType.FDS,
                    SecurityAuditLog.CheckResult.FAIL,
                    93,
                    "[F01] 단시간 고빈도 출금 (+40) [F02] 심야 시간대 이상 거래 (+30) [F03] 동일 주소 집중 출금 (+20) [F04] 단기 다실패 이력 (+3)",
                    false, true, ts.plusSeconds(2)));
        }

        repository.saveAll(seeds);
        log.info("[SecuritySeeder] 보안 감사 샘플 로그 {}건 삽입 완료 (7개 시나리오)", seeds.size());
    }

    // ── 빌더 헬퍼 ───────────────────────────────────────────────
    // createdAt 은 @CreationTimestamp 가 INSERT 시점에 자동 주입하므로 파라미터 불필요
    private SecurityAuditLog buildLog(
            String requestId, String callerEmail,
            String fromAddress, String toAddress,
            String tokenSymbol, BigDecimal amount,
            SecurityAuditLog.CheckType checkType,
            SecurityAuditLog.CheckResult checkResult,
            int riskScore, String blockReason,
            boolean kmsCallAllowed, boolean operatorNotified,
            LocalDateTime ignoredTs) {   // ignoredTs: 가독성 유지용, 실제 미사용

        return SecurityAuditLog.builder()
                .requestId(requestId)
                .callerEmail(callerEmail)
                .ipAddress(fakeIp(callerEmail))
                .fromAddress(fromAddress)
                .toAddress(toAddress)
                .tokenSymbol(tokenSymbol)
                .amount(amount)
                .checkType(checkType)
                .checkResult(checkResult)
                .riskScore(riskScore)
                .blockReason(blockReason)
                .kmsCallAllowed(kmsCallAllowed)
                .operatorNotified(operatorNotified)
                .build();
    }

    /** 이메일 기반 결정론적 가짜 IP 생성 */
    private String fakeIp(String email) {
        int h = Math.abs(email.hashCode());
        return (h % 223 + 1) + "." + (h % 197 + 1) + "." + (h % 251) + "." + (h % 254 + 1);
    }
}
