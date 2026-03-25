package com.tem.cchain.wallet.kms;

import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DERSequenceGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * KmsSignatureDecoder 단위 테스트.
 *
 * ---- TDD 관점 ----
 * 각 @Test는 독립적인 "요구사항"입니다:
 *   - "DER 서명을 r, s로 올바르게 파싱해야 한다"
 *   - "High-S 값은 Low-S로 정규화해야 한다 (EIP-2)"
 *   - "잘못된 DER 바이트는 예외를 던져야 한다"
 *
 * Mockito 불필요: 순수한 바이트 변환 로직이므로 외부 의존성 없음
 */
@DisplayName("KMS 서명 디코더 테스트")
class KmsSignatureDecoderTest {

    // secp256k1 곡선의 n (차수)
    private static final BigInteger CURVE_N = new BigInteger(
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16
    );

    @Test
    @DisplayName("유효한 DER 서명에서 r, s를 올바르게 추출한다")
    void decodeDerSignature_validInput_extractsRandS() throws Exception {
        // Given: 알려진 r, s 값으로 DER 시퀀스 생성
        // r은 정규화 대상이 아니므로 임의의 큰 값 사용
        BigInteger r = new BigInteger("A" + "0".repeat(63), 16);

        // ---- s 값 선택 주의 ----
        // halfN = 0x7FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF5D576E7357A4501DDFE92F46681B20A0
        // s 는 반드시 halfN 이하여야 정규화 없이 그대로 반환됨
        // "5" + 63개 "0" = 0x5000...000 → 첫 바이트 0x50 < halfN 첫 바이트 0x7F → 안전
        BigInteger s = new BigInteger("5" + "0".repeat(63), 16);

        byte[] derSignature = buildDerSignature(r, s);

        // When
        BigInteger[] result = KmsSignatureDecoder.decodeDerSignature(derSignature);

        // Then: Low-S 정규화 발생하지 않으므로 s 그대로 반환
        assertThat(result).hasSize(2);
        assertThat(result[0]).isEqualTo(r);
        assertThat(result[1]).isEqualTo(s);
        // s가 halfN 이하임을 이중 확인
        assertThat(result[1].compareTo(CURVE_N.shiftRight(1))).isLessThanOrEqualTo(0);
    }

    @Test
    @DisplayName("High-S 값은 n - s 로 정규화된다 (EIP-2 Low-S 규칙)")
    void decodeDerSignature_highS_normalizesToLowS() throws Exception {
        // Given: s가 n/2 보다 큰 "High-S" 값
        BigInteger r = new BigInteger("1" + "0".repeat(63), 16);
        BigInteger halfN = CURVE_N.shiftRight(1);
        BigInteger highS = halfN.add(BigInteger.ONE);  // n/2 + 1 (High-S)

        byte[] derSignature = buildDerSignature(r, highS);

        // When
        BigInteger[] result = KmsSignatureDecoder.decodeDerSignature(derSignature);

        // Then: s = n - highS (Low-S로 변환)
        BigInteger expectedLowS = CURVE_N.subtract(highS);
        assertThat(result[1]).isEqualTo(expectedLowS);
        assertThat(result[1].compareTo(halfN)).isLessThanOrEqualTo(0);  // Low-S 확인
    }

    @Test
    @DisplayName("잘못된 DER 바이트는 KmsSigningException을 던진다")
    void decodeDerSignature_invalidBytes_throwsException() {
        // Given: 랜덤 바이트 (유효하지 않은 DER)
        byte[] invalidDer = {0x01, 0x02, 0x03, 0x04};

        // When & Then
        assertThatThrownBy(() -> KmsSignatureDecoder.decodeDerSignature(invalidDer))
            .isInstanceOf(KmsSigningException.class)
            .hasMessageContaining("DER 서명 디코딩 실패");
    }

    @Test
    @DisplayName("toBytes32는 BigInteger를 32바이트 빅엔디안으로 변환한다")
    void toBytes32_smallValue_padsWith0() {
        // Given
        BigInteger value = BigInteger.valueOf(255);  // 0xFF

        // When
        byte[] result = KmsSignatureDecoder.toBytes32(value);

        // Then
        assertThat(result).hasSize(32);
        assertThat(result[31]).isEqualTo((byte) 0xFF);   // 마지막 바이트
        assertThat(result[0]).isEqualTo((byte) 0x00);    // 앞은 0 패딩
    }

    @Test
    @DisplayName("toBytes32는 33바이트 BigInteger(부호 비트 포함)를 올바르게 처리한다")
    void toBytes32_33byteValue_trimSignByte() {
        // Given: BigInteger.toByteArray()가 33바이트를 반환하는 경우
        // (최상위 비트가 1인 값에 부호 비트 0x00이 앞에 붙음)
        byte[] raw33 = new byte[33];
        raw33[0] = 0x00;  // 부호 비트
        raw33[1] = (byte) 0xFF;  // 실제 값 시작
        BigInteger value = new BigInteger(raw33);

        // When
        byte[] result = KmsSignatureDecoder.toBytes32(value);

        // Then: 32바이트여야 함
        assertThat(result).hasSize(32);
        assertThat(result[0]).isEqualTo((byte) 0xFF);
    }

    // ---- 테스트 헬퍼: r, s로 DER SEQUENCE 생성 ----
    private byte[] buildDerSignature(BigInteger r, BigInteger s) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DERSequenceGenerator seq = new DERSequenceGenerator(baos);
        seq.addObject(new ASN1Integer(r));
        seq.addObject(new ASN1Integer(s));
        seq.close();
        return baos.toByteArray();
    }
}
