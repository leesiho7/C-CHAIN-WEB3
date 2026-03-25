package com.tem.cchain.wallet.kms;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.util.PublicKeyFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Hash;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.Sign;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.rlp.RlpEncoder;
import org.web3j.rlp.RlpList;
import org.web3j.rlp.RlpString;
import org.web3j.rlp.RlpType;
import org.web3j.utils.Numeric;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.GetPublicKeyRequest;
import software.amazon.awssdk.services.kms.model.MessageType;
import software.amazon.awssdk.services.kms.model.SignRequest;
import software.amazon.awssdk.services.kms.model.SignResponse;
import software.amazon.awssdk.services.kms.model.SigningAlgorithmSpec;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * AWS KMS를 사용하여 Ethereum 트랜잭션에 서명하는 핵심 컴포넌트.
 *
 * ---- 왜 이게 필요한가? ----
 * 기존 방식: new Credentials(privateKey) → private key가 JVM 메모리에 평문 존재
 * KMS 방식:  KMS Key ID만 알고 있음 → 실제 서명은 AWS HSM 내부에서만 발생
 *            private key는 우리 코드, OS, 메모리 어디에도 존재하지 않음
 *
 * ---- AWS 설정 전제 조건 ----
 * 1. AWS KMS에서 ECC_SECG_P256K1 타입의 키를 생성
 * 2. EC2/ECS가 해당 키에 kms:Sign, kms:GetPublicKey 권한을 가진 IAM Role을 사용
 * 3. application.properties에 wallet.kms.key-id=arn:aws:kms:... 설정
 */
@Slf4j
@Component
public class KmsTransactionSigner {

    private final KmsClient kmsClient;

    @Value("${wallet.kms.key-id}")
    private String kmsKeyId;

    @Value("${wallet.chain-id:1}")
    private long chainId;

    // Ethereum 주소 (공개키에서 파생, 한 번만 계산)
    private volatile String cachedAddress;

    public KmsTransactionSigner(@Nullable KmsClient kmsClient) {
        this.kmsClient = kmsClient;
    }

    public boolean isAvailable() {
        return kmsClient != null;
    }

    /**
     * RawTransaction을 KMS로 서명하여 hex 인코딩된 서명된 트랜잭션을 반환합니다.
     * 이 hex를 eth_sendRawTransaction에 그대로 사용하면 됩니다.
     *
     * @param rawTransaction 서명할 트랜잭션 (nonce, gasPrice, gasLimit, to, value, data 포함)
     * @return "0x..."로 시작하는 서명된 트랜잭션 hex
     */
    public String signTransaction(RawTransaction rawTransaction) {
        if (kmsClient == null) throw new KmsSigningException("KMS 미설정 상태입니다. wallet.kms.key-id를 확인하세요.");
        // 1. EIP-155 포함 트랜잭션 바이트를 RLP 인코딩
        //    TransactionEncoder.encode(tx, chainId)는 [nonce, gasPrice, gasLimit, to, value, data, chainId, 0, 0] 형태로 인코딩
        byte[] encodedTransaction = TransactionEncoder.encode(rawTransaction, chainId);

        // 2. keccak256 해시 계산 (이 해시를 KMS에 서명 요청)
        byte[] transactionHash = Hash.sha3(encodedTransaction);
        log.debug("[KMS] 트랜잭션 해시: {}", Numeric.toHexString(transactionHash));

        // 3. AWS KMS에 서명 요청
        SignResponse signResponse = kmsClient.sign(SignRequest.builder()
            .keyId(kmsKeyId)
            .message(SdkBytes.fromByteArray(transactionHash))
            .messageType(MessageType.DIGEST)        // 이미 해시된 값 전달
            .signingAlgorithm(SigningAlgorithmSpec.ECDSA_SHA_256)
            .build());

        byte[] derSignature = signResponse.signature().asByteArray();
        log.debug("[KMS] DER 서명 수신 ({}바이트)", derSignature.length);

        // 4. DER → (r, s) 추출
        BigInteger[] rs = KmsSignatureDecoder.decodeDerSignature(derSignature);
        BigInteger r = rs[0];
        BigInteger s = rs[1];

        // 5. v 값 복구 (27 또는 28 중 어느 게 올바른 공개키와 매칭되는지 확인)
        int recId = findRecoveryId(transactionHash, r, s);

        // EIP-155 적용: v = recId + chainId * 2 + 35
        BigInteger v = BigInteger.valueOf(recId + chainId * 2 + 35);
        log.debug("[KMS] 서명 완료 v={}, recId={}", v, recId);

        // 6. 서명된 트랜잭션 RLP 조립
        byte[] signedTx = encodeSignedTransaction(rawTransaction, v, r, s);
        return Numeric.toHexString(signedTx);
    }

    /**
     * 이 KMS 키에 해당하는 Ethereum 주소를 반환합니다.
     * AWS KMS GetPublicKey → SubjectPublicKeyInfo DER 파싱 → keccak256 → 마지막 20바이트
     */
    public String getEthereumAddress() {
        if (cachedAddress != null) return cachedAddress;

        synchronized (this) {
            if (cachedAddress != null) return cachedAddress;

            try {
                byte[] publicKeyDer = kmsClient.getPublicKey(
                    GetPublicKeyRequest.builder().keyId(kmsKeyId).build()
                ).publicKey().asByteArray();

                // DER SubjectPublicKeyInfo → 비압축 공개키 포인트 (65바이트: 0x04 + 32 + 32)
                SubjectPublicKeyInfo spki = SubjectPublicKeyInfo.getInstance(publicKeyDer);
                ECPublicKeyParameters ecParams = (ECPublicKeyParameters) PublicKeyFactory.createKey(spki);
                byte[] uncompressedPoint = ecParams.getQ().getEncoded(false); // false = 비압축

                // Ethereum: 0x04 prefix 제거 후 keccak256, 마지막 20바이트
                byte[] withoutPrefix = Arrays.copyOfRange(uncompressedPoint, 1, uncompressedPoint.length);
                byte[] hash = Hash.sha3(withoutPrefix);
                byte[] addressBytes = Arrays.copyOfRange(hash, 12, 32);

                cachedAddress = "0x" + Numeric.toHexString(addressBytes).substring(2);
                log.info("[KMS] 서버 지갑 주소: {}", cachedAddress);
            } catch (Exception e) {
                throw new KmsSigningException("KMS 공개키 조회 실패", e);
            }
        }
        return cachedAddress;
    }

    /**
     * v(복구 ID) 결정: 서명 (r, s)에서 복구한 공개키가 KMS 공개키와 일치하는 recId를 찾습니다.
     * recId는 0 또는 1 중 하나입니다.
     */
    private int findRecoveryId(byte[] hash, BigInteger r, BigInteger s) {
        String expectedAddress = getEthereumAddress().toLowerCase();

        for (int recId = 0; recId < 2; recId++) {
            try {
                // Sign.SignatureData 생성 (임시 v: recId + 27)
                Sign.SignatureData signatureData = new Sign.SignatureData(
                    (byte)(recId + 27),
                    KmsSignatureDecoder.toBytes32(r),
                    KmsSignatureDecoder.toBytes32(s)
                );

                // 공개키 복구
                BigInteger recoveredKey = Sign.signedMessageHashToKey(hash, signatureData);
                String recoveredAddress = "0x" + Keys.getAddress(recoveredKey);

                if (recoveredAddress.equalsIgnoreCase(expectedAddress)) {
                    return recId;
                }
            } catch (Exception e) {
                // recId가 틀린 경우 예외 발생 가능 → 계속 시도
                log.trace("[KMS] recId={} 복구 실패, 다음 시도", recId);
            }
        }
        throw new KmsSigningException("복구 ID를 찾을 수 없습니다. KMS 키 설정을 확인하세요.");
    }

    /**
     * EIP-155 서명된 트랜잭션을 RLP 인코딩합니다.
     * [nonce, gasPrice, gasLimit, to, value, data, v, r, s]
     */
    private byte[] encodeSignedTransaction(RawTransaction tx, BigInteger v, BigInteger r, BigInteger s) {
        List<RlpType> values = new ArrayList<>();
        values.add(RlpString.create(tx.getNonce()));
        values.add(RlpString.create(tx.getGasPrice()));
        values.add(RlpString.create(tx.getGasLimit()));
        values.add(RlpString.create(Numeric.hexStringToByteArray(tx.getTo())));
        values.add(RlpString.create(tx.getValue()));
        values.add(RlpString.create(Numeric.hexStringToByteArray(tx.getData() == null ? "0x" : tx.getData())));
        values.add(RlpString.create(v));
        values.add(RlpString.create(KmsSignatureDecoder.toBytes32(r)));
        values.add(RlpString.create(KmsSignatureDecoder.toBytes32(s)));
        return RlpEncoder.encode(new RlpList(values));
    }

    // web3j Keys 유틸리티 내부 참조용 alias
    private static class Keys {
        static String getAddress(BigInteger publicKey) {
            return org.web3j.crypto.Keys.getAddress(publicKey);
        }
    }
}
