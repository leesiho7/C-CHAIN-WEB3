package com.tem.cchain.entity; // 패키지 경로 꼭 확인!

import java.time.LocalDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Data
@NoArgsConstructor
@Getter
@Setter
public class Member {
    @Id
    @Column(length = 100)
    private String email; 
    
    @Column(unique = true, nullable = false, length = 50)
    private String userid; 
    
    @Column(nullable = false)
    private String userpw; 
    // MetaMask에서 연결한 이더리움 주소 (0x...)
    // 유저의 개인키는 MetaMask가 관리 → 서버는 절대 보관하지 않음
    private String walletaddress;

    // privateKey 필드 제거됨 (KMS/MetaMask 하이브리드 전환)
    // DB 컬럼은 hibernate ddl-auto=update 특성상 자동 삭제되지 않으므로
    // 운영 DB에서는 직접 ALTER TABLE member DROP COLUMN private_key; 실행 권장

    //db상의 omt 잔고
    private java.math.BigDecimal omtBalance;

    // 입금된 USDT/USDC 잔고 (플랫폼 내부 단위)
    private java.math.BigDecimal usdtBalance;

    // 입금된 ETH 잔고 (플랫폼 내부 단위, Wei가 아닌 ETH 단위)
    private java.math.BigDecimal ethDepositBalance;

    // 지갑 소유권 인증 완료 여부 (personal_sign SIWE 검증)
    private Boolean walletVerified = false;
    
    private Long verifiedCases = 0L;
    
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
    
    // 추가 : 기여도가 상승할때 호출할 메서드
    public void incrementVerifiedCases() {
    	this.verifiedCases = (this.verifiedCases == null ? 0 : this.verifiedCases) + 1;
    }
    
    
}