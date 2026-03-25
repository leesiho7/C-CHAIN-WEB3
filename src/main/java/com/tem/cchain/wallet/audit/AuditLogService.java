package com.tem.cchain.wallet.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 지갑 작업의 모든 감사 이벤트를 기록하는 서비스.
 *
 * ---- 핵심 설계 결정 ----
 *
 * 1. 비동기(@Async): 감사 로그 저장이 트랜잭션 응답을 느리게 하면 안 됨
 *    - 단, 중요 보안 이벤트(ROLE_DENIED, POLICY_DENIED)는 동기로 저장 고려
 *
 * 2. 독립 트랜잭션(REQUIRES_NEW): 메인 트랜잭션 롤백 시에도 감사 로그는 유지
 *    - "전송 실패" 자체가 감사 이벤트이므로 롤백되면 안 됨
 *
 * 3. MDC Correlation ID: 분산 로깅에서 같은 요청의 로그를 묶어 추적
 *    - 예: transfer 요청 → KMS 서명 → 블록체인 전송 → 잔액 동기화
 *      모두 같은 correlationId로 연결
 *
 * 4. Never Throw: 감사 로그 실패가 비즈니스 로직을 중단시키면 안 됨
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditRepository auditRepository;

    /**
     * 성공한 전송 작업 기록.
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logTransfer(String toAddress, BigDecimal amount, String tokenType,
                            String txHash, WalletOperation operation) {
        saveQuietly(AuditEvent.builder()
            .operation(operation)
            .result("SUCCESS")
            .callerEmail(getCurrentEmail())
            .callerRole(getCurrentRole())
            .callerIp(getCurrentIp())
            .toAddress(toAddress)
            .amount(amount)
            .tokenType(tokenType)
            .txHash(txHash)
            .correlationId(getCorrelationId())
            .build());
    }

    /**
     * 정책 거부 이벤트 기록.
     * 보안 모니터링에서 가장 중요한 이벤트 중 하나입니다.
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logPolicyDenied(String toAddress, BigDecimal amount, String reason) {
        saveQuietly(AuditEvent.builder()
            .operation(WalletOperation.POLICY_DENIED)
            .result("DENIED")
            .callerEmail(getCurrentEmail())
            .callerRole(getCurrentRole())
            .callerIp(getCurrentIp())
            .toAddress(toAddress)
            .amount(amount)
            .correlationId(getCorrelationId())
            .details("정책 거부: " + reason)
            .build());

        // 보안 이벤트는 warn 레벨로 별도 로그 (SIEM 시스템으로 전달 가능)
        log.warn("[AUDIT-SECURITY] 정책 거부: caller={}, to={}, amount={}, reason={}",
            getCurrentEmail(), toAddress, amount, reason);
    }

    /**
     * 역할 접근 거부 이벤트 기록.
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logRoleDenied(String methodName, String requiredRole) {
        saveQuietly(AuditEvent.builder()
            .operation(WalletOperation.ROLE_DENIED)
            .result("DENIED")
            .callerEmail(getCurrentEmail())
            .callerRole(getCurrentRole())
            .callerIp(getCurrentIp())
            .correlationId(getCorrelationId())
            .details(String.format("역할 접근 거부: method=%s, required=%s", methodName, requiredRole))
            .build());

        log.warn("[AUDIT-SECURITY] 역할 접근 거부: caller={}, method={}, required={}",
            getCurrentEmail(), methodName, requiredRole);
    }

    /**
     * 실패한 작업 기록.
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logFailure(WalletOperation operation, String toAddress, BigDecimal amount,
                           String errorMessage) {
        saveQuietly(AuditEvent.builder()
            .operation(operation)
            .result("FAILURE")
            .callerEmail(getCurrentEmail())
            .callerRole(getCurrentRole())
            .callerIp(getCurrentIp())
            .toAddress(toAddress)
            .amount(amount)
            .correlationId(getCorrelationId())
            .details("오류: " + errorMessage)
            .build());
    }

    /**
     * 오늘 특정 사용자의 누적 전송량 조회.
     * PolicyEngine의 일일 한도 검사에 사용됩니다.
     */
    @Transactional(readOnly = true)
    public BigDecimal getDailyTransferTotal(String email) {
        Instant startOfDay = Instant.now()
            .truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        return auditRepository.sumSuccessfulTransfersSince(email, startOfDay);
    }

    // ---- private helpers ----

    private void saveQuietly(AuditEvent event) {
        try {
            auditRepository.save(event);
        } catch (Exception e) {
            // 감사 로그 저장 실패가 비즈니스 로직을 죽이면 안 됨
            // 하지만 반드시 로그는 남겨야 함
            log.error("[AUDIT] 감사 이벤트 저장 실패 (operation={}): {}",
                event.getOperation(), e.getMessage());
        }
    }

    private String getCurrentEmail() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            return auth != null ? auth.getName() : "anonymous";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String getCurrentRole() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null) return "NONE";
            return auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .filter(a -> a.startsWith("ROLE_WALLET_"))
                .findFirst()
                .map(a -> a.substring("ROLE_WALLET_".length()))
                .orElse("UNKNOWN");
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String getCurrentIp() {
        // MDC에 IP가 설정되어 있으면 사용 (필터에서 설정)
        String ip = MDC.get("clientIp");
        return ip != null ? ip : "unknown";
    }

    private String getCorrelationId() {
        String correlationId = MDC.get("correlationId");
        return correlationId != null ? correlationId : java.util.UUID.randomUUID().toString();
    }
}
