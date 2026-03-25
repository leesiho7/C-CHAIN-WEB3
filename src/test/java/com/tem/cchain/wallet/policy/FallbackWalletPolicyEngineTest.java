package com.tem.cchain.wallet.policy;

import com.tem.cchain.wallet.policy.dto.PolicyDecision;
import com.tem.cchain.wallet.policy.dto.PolicyRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

/**
 * FallbackWalletPolicyEngine 단위 테스트.
 *
 * ---- 테스트 전략 ----
 * @Nested 클래스로 "시나리오 그룹"을 만들어 가독성을 높입니다.
 * 각 룰(RULE-01 ~ RULE-05)을 독립적으로 검증합니다.
 *
 * Mockito 불필요: 순수 도메인 로직이므로 외부 의존성 없음
 */
@DisplayName("Fallback 정책 엔진 테스트")
class FallbackWalletPolicyEngineTest {

    private FallbackWalletPolicyEngine engine;

    @BeforeEach
    void setUp() {
        engine = new FallbackWalletPolicyEngine();
        // @Value 필드는 ReflectionTestUtils로 주입 (Spring Context 없이 테스트)
        ReflectionTestUtils.setField(engine, "maxSingleTransfer", new BigDecimal("10000"));
        ReflectionTestUtils.setField(engine, "maxDailyTransfer", new BigDecimal("100000"));
    }

    // ---- 헬퍼: 기본 유효한 요청 빌더 ----
    private PolicyRequest.PolicyRequestBuilder validRequest() {
        return PolicyRequest.builder()
            .callerRole("MASTER")
            .toAddress("0xAbCdEf1234567890AbCdEf1234567890AbCdEf12")
            .amount(new BigDecimal("100"))
            .tokenType("OMT")
            .dailyTotal(new BigDecimal("500"))
            .callerIp("10.0.0.1")
            .operationType("TRANSFER");
    }

    @Nested
    @DisplayName("RULE-01: 1회 전송 한도")
    class SingleTransferLimit {

        @Test
        @DisplayName("한도 이하 전송은 허용된다")
        void belowLimit_allowed() {
            PolicyDecision decision = engine.evaluate(
                validRequest().amount(new BigDecimal("9999")).build()
            );
            assertThat(decision.isAllow()).isTrue();
        }

        @Test
        @DisplayName("한도 정확히 = 허용된다 (경계값)")
        void exactLimit_allowed() {
            PolicyDecision decision = engine.evaluate(
                validRequest().amount(new BigDecimal("10000")).build()
            );
            assertThat(decision.isAllow()).isTrue();
        }

        @Test
        @DisplayName("한도 초과 전송은 거부된다")
        void exceedsLimit_denied() {
            PolicyDecision decision = engine.evaluate(
                validRequest().amount(new BigDecimal("10001")).build()
            );
            assertThat(decision.isAllow()).isFalse();
            assertThat(decision.getReason()).contains("1회 전송 한도 초과");
            assertThat(decision.getPolicyId()).isEqualTo("RULE-01");
        }
    }

    @Nested
    @DisplayName("RULE-02: 일일 누적 전송 한도")
    class DailyTransferLimit {

        @Test
        @DisplayName("일일 누적이 한도 미만이면 허용된다")
        void belowDailyLimit_allowed() {
            PolicyDecision decision = engine.evaluate(
                validRequest()
                    .dailyTotal(new BigDecimal("99000"))
                    .amount(new BigDecimal("999"))
                    .build()
            );
            assertThat(decision.isAllow()).isTrue();
        }

        @Test
        @DisplayName("누적 + 현재 금액이 일일 한도를 초과하면 거부된다")
        void exceedsDailyLimit_denied() {
            PolicyDecision decision = engine.evaluate(
                validRequest()
                    .dailyTotal(new BigDecimal("99000"))  // 이미 99,000
                    .amount(new BigDecimal("1001"))       // + 1,001 = 100,001 초과
                    .build()
            );
            assertThat(decision.isAllow()).isFalse();
            assertThat(decision.getReason()).contains("일일 전송 한도 초과");
            assertThat(decision.getPolicyId()).isEqualTo("RULE-02");
        }
    }

    @Nested
    @DisplayName("RULE-03: 블랙리스트 주소")
    class BlacklistAddress {

        @Test
        @DisplayName("Zero address (0x000...000)는 거부된다")
        void zeroAddress_denied() {
            PolicyDecision decision = engine.evaluate(
                validRequest()
                    .toAddress("0x0000000000000000000000000000000000000000")
                    .build()
            );
            assertThat(decision.isAllow()).isFalse();
            assertThat(decision.getReason()).contains("블랙리스트");
            assertThat(decision.getPolicyId()).isEqualTo("RULE-03");
        }

        @Test
        @DisplayName("일반 주소는 허용된다")
        void normalAddress_allowed() {
            PolicyDecision decision = engine.evaluate(
                validRequest()
                    .toAddress("0x1234567890123456789012345678901234567890")
                    .build()
            );
            assertThat(decision.isAllow()).isTrue();
        }
    }

    @Nested
    @DisplayName("RULE-04: 역할 기반 접근")
    class RoleBasedAccess {

        @Test
        @DisplayName("READ_ONLY 역할은 TRANSFER를 할 수 없다")
        void readOnly_cannotTransfer() {
            PolicyDecision decision = engine.evaluate(
                validRequest().callerRole("READ_ONLY").build()
            );
            assertThat(decision.isAllow()).isFalse();
            assertThat(decision.getReason()).contains("READ_ONLY 역할은 전송 권한이 없습니다");
            assertThat(decision.getPolicyId()).isEqualTo("RULE-04");
        }

        @Test
        @DisplayName("READ_ONLY 역할은 BALANCE_CHECK를 할 수 있다")
        void readOnly_canCheckBalance() {
            PolicyDecision decision = engine.evaluate(
                validRequest()
                    .callerRole("READ_ONLY")
                    .operationType("BALANCE_CHECK")
                    .build()
            );
            assertThat(decision.isAllow()).isTrue();
        }

        @Test
        @DisplayName("REWARD 역할은 REWARD 작업을 할 수 있다")
        void reward_canPayReward() {
            PolicyDecision decision = engine.evaluate(
                validRequest()
                    .callerRole("REWARD")
                    .operationType("REWARD")
                    .build()
            );
            assertThat(decision.isAllow()).isTrue();
        }
    }

    @Nested
    @DisplayName("RULE-05: 주소 형식 검증")
    class AddressFormatValidation {

        @Test
        @DisplayName("0x 없는 주소는 거부된다")
        void addressWithoutPrefix_denied() {
            PolicyDecision decision = engine.evaluate(
                validRequest()
                    .toAddress("1234567890123456789012345678901234567890")
                    .build()
            );
            assertThat(decision.isAllow()).isFalse();
            assertThat(decision.getPolicyId()).isEqualTo("RULE-05");
        }

        @Test
        @DisplayName("39자리 주소는 거부된다")
        void shortAddress_denied() {
            PolicyDecision decision = engine.evaluate(
                validRequest()
                    .toAddress("0x12345678901234567890123456789012345678") // 38자 (40 미만)
                    .build()
            );
            assertThat(decision.isAllow()).isFalse();
        }

        @Test
        @DisplayName("null 주소는 거부된다")
        void nullAddress_denied() {
            PolicyDecision decision = engine.evaluate(
                validRequest().toAddress(null).build()
            );
            assertThat(decision.isAllow()).isFalse();
        }
    }

    @Test
    @DisplayName("모든 조건을 만족하는 정상 요청은 허용된다")
    void validRequest_allowed() {
        PolicyDecision decision = engine.evaluate(validRequest().build());
        assertThat(decision.isAllow()).isTrue();
    }
}
