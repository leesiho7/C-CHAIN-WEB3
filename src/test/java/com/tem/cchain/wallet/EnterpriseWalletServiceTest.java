package com.tem.cchain.wallet;

import com.tem.cchain.wallet.audit.AuditLogService;
import com.tem.cchain.wallet.audit.WalletOperation;
import com.tem.cchain.wallet.iam.IamRoleService;
import com.tem.cchain.wallet.iam.WalletRole;
import com.tem.cchain.wallet.kms.KmsTransactionSigner;
import com.tem.cchain.wallet.policy.PolicyEngine;
import com.tem.cchain.wallet.policy.dto.PolicyDecision;
import com.tem.cchain.wallet.policy.dto.PolicyRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.EthSendTransaction;

import static org.mockito.Mockito.doReturn;

import java.math.BigDecimal;
import java.math.BigInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

/**
 * EnterpriseWalletService 통합 단위 테스트.
 *
 * ---- Mockito 사용법 설명 ----
 * @Mock: 가짜 객체 생성 (실제 구현 없이 원하는 동작 설정)
 * @InjectMocks: @Mock 객체들을 생성자/필드로 주입
 * given().willReturn(): "이 메서드가 호출되면 이 값을 반환해라"
 * verify(): "이 메서드가 이 인자로 호출됐는지 검증"
 * ArgumentCaptor: "어떤 인자로 호출됐는지 캡처해서 검증"
 *
 * ---- 테스트 전략 ----
 * 외부 의존성(AWS KMS, Web3j, DB)을 모두 Mock으로 대체
 * → 빠르고 결정적인(deterministic) 테스트
 * → 네트워크/AWS 없이 CI/CD에서 실행 가능
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EnterpriseWalletService 테스트")
class EnterpriseWalletServiceTest {

    @Mock
    private ObjectProvider<Web3j> web3jProvider;

    @Mock
    private Web3j web3j;

    @Mock
    private KmsTransactionSigner kmsSigner;

    @Mock
    private PolicyEngine policyEngine;

    @Mock
    private IamRoleService iamRoleService;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private EnterpriseWalletService walletService;

    private static final String VALID_TO_ADDRESS = "0x1234567890123456789012345678901234567890";
    private static final String CONTRACT_ADDRESS = "0xabcdef1234567890abcdef1234567890abcdef12";
    private static final String TX_HASH = "0xdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(walletService, "omtContractAddress", CONTRACT_ADDRESS);
    }

    // ================================================================
    // transferOmt 테스트
    // ================================================================
    @Nested
    @DisplayName("transferOmt - OMT 토큰 전송")
    class TransferOmtTest {

        @Test
        @DisplayName("정책 허용 + KMS 서명 성공 → 트랜잭션 해시 반환")
        void transferOmt_policyAllowed_returnsTxHash() throws Exception {
            // Given
            BigDecimal amount = new BigDecimal("100");

            // PolicyEngine이 허용 반환
            given(policyEngine.evaluate(any(PolicyRequest.class)))
                .willReturn(PolicyDecision.allow("RULE-OK"));

            // ObjectProvider → Web3j 반환 설정
            given(web3jProvider.getIfAvailable()).willReturn(web3j);

            // KMS 서명 주소 및 서명된 tx 반환
            given(kmsSigner.getEthereumAddress()).willReturn("0xServerWallet");
            given(kmsSigner.signTransaction(any())).willReturn("0xsignedTxHex");

            // Web3j Nonce 조회 Mock
            mockNonceResponse(BigInteger.valueOf(42));

            // Web3j 전송 Mock
            mockSendTransactionSuccess(TX_HASH);

            // AuditLogService의 일일 합계 조회
            given(auditLogService.getDailyTransferTotal(any())).willReturn(BigDecimal.ZERO);

            // When
            String result = walletService.transferOmt(VALID_TO_ADDRESS, amount);

            // Then
            assertThat(result).isEqualTo(TX_HASH);

            // 감사 로그가 기록됐는지 검증
            verify(auditLogService).logTransfer(
                eq(VALID_TO_ADDRESS),
                eq(amount),
                eq("OMT"),
                eq(TX_HASH),
                eq(WalletOperation.MASTER_TRANSFER)
            );
        }

        @Test
        @DisplayName("정책 거부 → PolicyViolationException 던지고 감사 로그 기록")
        void transferOmt_policyDenied_throwsExceptionAndLogs() {
            // Given
            BigDecimal amount = new BigDecimal("99999");  // 한도 초과
            String denyReason = "1회 전송 한도 초과";

            given(policyEngine.evaluate(any(PolicyRequest.class)))
                .willReturn(PolicyDecision.deny(denyReason, "RULE-01"));
            given(auditLogService.getDailyTransferTotal(any())).willReturn(BigDecimal.ZERO);

            // When & Then
            assertThatThrownBy(() -> walletService.transferOmt(VALID_TO_ADDRESS, amount))
                .isInstanceOf(EnterpriseWalletService.PolicyViolationException.class)
                .hasMessageContaining(denyReason);

            // 정책 거부 감사 로그가 기록됐는지 확인
            verify(auditLogService).logPolicyDenied(
                eq(VALID_TO_ADDRESS),
                eq(amount),
                eq(denyReason)
            );

            // 전송 감사 로그는 기록되지 않아야 함
            verify(auditLogService, never()).logTransfer(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("정책 평가 시 PolicyRequest에 일일 누적량이 포함된다")
        void transferOmt_includesDailyTotalInPolicyRequest() {
            // Given
            BigDecimal dailyTotal = new BigDecimal("5000");
            BigDecimal amount = new BigDecimal("100");

            given(auditLogService.getDailyTransferTotal(any())).willReturn(dailyTotal);
            given(policyEngine.evaluate(any()))
                .willReturn(PolicyDecision.deny("테스트용 거부", "TEST"));

            // ArgumentCaptor로 PolicyRequest 내용 캡처
            ArgumentCaptor<PolicyRequest> captor = ArgumentCaptor.forClass(PolicyRequest.class);

            // When
            try {
                walletService.transferOmt(VALID_TO_ADDRESS, amount);
            } catch (Exception e) {
                // 거부 예외는 무시
            }

            // Then: PolicyEngine에 전달된 요청에 dailyTotal이 포함됐는지 검증
            verify(policyEngine).evaluate(captor.capture());
            PolicyRequest capturedRequest = captor.getValue();
            assertThat(capturedRequest.getDailyTotal()).isEqualTo(dailyTotal);
            assertThat(capturedRequest.getAmount()).isEqualTo(amount);
            assertThat(capturedRequest.getOperationType()).isEqualTo("TRANSFER");
        }

        @Test
        @DisplayName("블록체인 전송 실패 → WalletTransactionException + 실패 감사 로그")
        void transferOmt_blockchainError_throwsAndLogsFailure() throws Exception {
            // Given
            given(policyEngine.evaluate(any())).willReturn(PolicyDecision.allow("OK"));
            given(web3jProvider.getIfAvailable()).willReturn(web3j);
            given(kmsSigner.getEthereumAddress()).willReturn("0xServerWallet");
            given(kmsSigner.signTransaction(any())).willReturn("0xsignedTx");
            given(auditLogService.getDailyTransferTotal(any())).willReturn(BigDecimal.ZERO);
            mockNonceResponse(BigInteger.ONE);
            mockSendTransactionError("insufficient funds");

            // When & Then
            assertThatThrownBy(() ->
                walletService.transferOmt(VALID_TO_ADDRESS, new BigDecimal("100"))
            ).isInstanceOf(EnterpriseWalletService.WalletTransactionException.class);

            // 실패 감사 로그 기록 확인
            verify(auditLogService).logFailure(
                eq(WalletOperation.MASTER_TRANSFER),
                eq(VALID_TO_ADDRESS),
                any(),
                anyString()
            );
        }
    }

    // ================================================================
    // payReward 테스트
    // ================================================================
    @Nested
    @DisplayName("payReward - 기여 보상 지급")
    class PayRewardTest {

        @Test
        @DisplayName("REWARD 역할은 보상을 지급할 수 있다")
        void payReward_rewardRole_succeeds() throws Exception {
            // Given
            BigDecimal rewardAmount = new BigDecimal("50");
            given(policyEngine.evaluate(any())).willReturn(PolicyDecision.allow("OK"));
            given(web3jProvider.getIfAvailable()).willReturn(web3j);
            given(kmsSigner.getEthereumAddress()).willReturn("0xServerWallet");
            given(kmsSigner.signTransaction(any())).willReturn("0xsignedTx");
            given(auditLogService.getDailyTransferTotal(any())).willReturn(BigDecimal.ZERO);
            mockNonceResponse(BigInteger.TWO);
            mockSendTransactionSuccess(TX_HASH);

            // When
            String result = walletService.payReward(VALID_TO_ADDRESS, rewardAmount);

            // Then
            assertThat(result).isEqualTo(TX_HASH);
            verify(auditLogService).logTransfer(
                any(), any(), any(), eq(TX_HASH), eq(WalletOperation.REWARD_PAYMENT)
            );
        }
    }

    // ================================================================
    // Mock 헬퍼 메서드
    // ================================================================

    // ---- 핵심: doReturn().when() 패턴 ----
    // given().willReturn()은 제네릭 타입을 엄격하게 검사해서 argument mismatch 발생.
    // doReturn().when()은 타입 체크를 우회하므로 web3j의 복잡한 제네릭 Request 타입에 적합.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void mockNonceResponse(BigInteger nonce) throws Exception {
        EthGetTransactionCount response = mock(EthGetTransactionCount.class);
        given(response.getTransactionCount()).willReturn(nonce);

        org.web3j.protocol.core.Request request = mock(org.web3j.protocol.core.Request.class);
        given(request.send()).willReturn(response);

        doReturn(request).when(web3j).ethGetTransactionCount(anyString(), any());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void mockSendTransactionSuccess(String txHash) throws Exception {
        EthSendTransaction sendTransaction = mock(EthSendTransaction.class);
        given(sendTransaction.hasError()).willReturn(false);
        given(sendTransaction.getTransactionHash()).willReturn(txHash);

        org.web3j.protocol.core.Request request = mock(org.web3j.protocol.core.Request.class);
        given(request.send()).willReturn(sendTransaction);

        doReturn(request).when(web3j).ethSendRawTransaction(anyString());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void mockSendTransactionError(String errorMessage) throws Exception {
        EthSendTransaction sendTransaction = mock(EthSendTransaction.class);
        given(sendTransaction.hasError()).willReturn(true);

        org.web3j.protocol.core.Response.Error error =
            mock(org.web3j.protocol.core.Response.Error.class);
        given(error.getMessage()).willReturn(errorMessage);
        given(sendTransaction.getError()).willReturn(error);

        org.web3j.protocol.core.Request request = mock(org.web3j.protocol.core.Request.class);
        given(request.send()).willReturn(sendTransaction);

        doReturn(request).when(web3j).ethSendRawTransaction(anyString());
    }
}
