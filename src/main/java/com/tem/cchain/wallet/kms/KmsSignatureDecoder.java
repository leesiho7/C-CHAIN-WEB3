package com.tem.cchain.wallet.kms;

import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;

import java.math.BigInteger;

/**
 * AWS KMS가 반환하는 DER 인코딩 ECDSA 서명을 Ethereum 서명 형식(r, s)으로 변환합니다.
 *
 * ---- DER 구조 설명 ----
 * ECDSA 서명은 ASN.1 DER 포맷으로 인코딩됩니다:
 *
 *   0x30 [전체 길이]           <- SEQUENCE 시작
 *     0x02 [r 길이] [r 바이트] <- INTEGER r
 *     0x02 [s 길이] [s 바이트] <- INTEGER s
 *
 * Ethereum은 이 r, s를 32바이트씩 그냥 사용하고, 거기에 v(복구 ID)를 붙입니다.
 * v는 27 또는 28 (EIP-155 이후: chainId * 2 + 35/36)
 */
public class KmsSignatureDecoder {

    private KmsSignatureDecoder() {}

    /**
     * DER 인코딩된 서명 바이트에서 r, s 두 BigInteger를 추출합니다.
     *
     * @param derSignature AWS KMS.sign() 응답으로 받은 DER 바이트
     * @return [r, s] 배열
     */
    public static BigInteger[] decodeDerSignature(byte[] derSignature) {
        try {
            // ASN1Sequence.getInstance(byte[])는 버전에 따라 동작이 다름
            // → ASN1Primitive.fromByteArray()로 먼저 파싱 후 Sequence로 캐스팅 (안전)
            ASN1Primitive primitive = ASN1Primitive.fromByteArray(derSignature);
            ASN1Sequence sequence = ASN1Sequence.getInstance(primitive);

            // 첫 번째 INTEGER = r
            BigInteger r = ASN1Integer.getInstance(sequence.getObjectAt(0)).getValue();
            // 두 번째 INTEGER = s
            BigInteger s = ASN1Integer.getInstance(sequence.getObjectAt(1)).getValue();

            // ---- s 정규화 (EIP-2 Low-S 규칙) ----
            // secp256k1 곡선의 n(차수) 값
            BigInteger curveN = new BigInteger(
                "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16
            );
            // s가 n/2 보다 크면 s = n - s 로 정규화 (서명 가변성 방지)
            BigInteger halfN = curveN.shiftRight(1);
            if (s.compareTo(halfN) > 0) {
                s = curveN.subtract(s);
            }

            return new BigInteger[]{r, s};
        } catch (Exception e) {
            throw new KmsSigningException("DER 서명 디코딩 실패: " + e.getMessage(), e);
        }
    }

    /**
     * r, s를 각각 32바이트 빅엔디안으로 패딩합니다.
     * Ethereum Sign Tx 조립 시 사용됩니다.
     */
    public static byte[] toBytes32(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] result = new byte[32];

        if (raw.length <= 32) {
            // 오른쪽 정렬 (앞에 0 패딩)
            System.arraycopy(raw, 0, result, 32 - raw.length, raw.length);
        } else {
            // BigInteger.toByteArray()는 부호 비트로 앞에 0x00이 붙을 수 있음 → 뒤 32바이트만 사용
            System.arraycopy(raw, raw.length - 32, result, 0, 32);
        }
        return result;
    }
}
