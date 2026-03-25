package com.tem.cchain.wallet.kms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;

/**
 * AWS KMS 클라이언트 설정.
 *
 * ---- 인증 방식 (DefaultCredentialsProvider 우선순위) ----
 * 1. 환경변수:  AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY
 * 2. JVM 속성: -Daws.accessKeyId, -Daws.secretAccessKey
 * 3. ~/.aws/credentials 파일 (로컬 개발)
 * 4. EC2/ECS 인스턴스 IAM Role 메타데이터 (프로덕션 권장)
 *    → EC2에 IAM Role만 붙여주면 키 없이 자동 인증됨!
 *
 * ---- 필요한 IAM 권한 ----
 * {
 *   "Action": ["kms:Sign", "kms:GetPublicKey"],
 *   "Effect": "Allow",
 *   "Resource": "arn:aws:kms:ap-southeast-2:052464239820:key/79eb15ae-..."
 * }
 *
 * ---- KMS 비활성화 (로컬/테스트) ----
 * wallet.kms.key-id=none 으로 설정하면 KmsClient 빈을 null로 반환
 * → KmsTransactionSigner 미동작, EnterpriseWalletService는 Web3j 미연결 상태로 처리
 */
@Slf4j
@Configuration
public class KmsConfig {

    @Value("${wallet.kms.region:ap-southeast-2}")
    private String region;

    @Value("${wallet.kms.key-id:none}")
    private String keyId;

    @Bean
    public KmsClient kmsClient() {
        if ("none".equals(keyId) || keyId == null || keyId.isBlank()) {
            log.warn("[KMS] wallet.kms.key-id가 설정되지 않았습니다. KmsClient 빈이 생성되지 않습니다.");
            return null;
        }
        try {
            log.info("[KMS] KmsClient 초기화: region={}, keyId={}", region, keyId);
            return KmsClient.builder()
                .region(Region.of(region))
                // DefaultCredentialsProvider: 환경변수 → ~/.aws → EC2 IAM Role 순서로 자동 탐색
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
        } catch (Exception e) {
            log.error("[KMS] KmsClient 초기화 실패: {}", e.getMessage());
            return null;
        }
    }
}
