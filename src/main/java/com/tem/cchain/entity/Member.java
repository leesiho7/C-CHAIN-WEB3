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
    //실제 이더리움 입금 주소 (0x.....)
    private String walletaddress; 
    //주소 개인키(실제 운영시에는 반드시ㅣ 강력하게 암호화 해야함!!)
    private String privateKey;
    
    //db상의 omt 잔고
    private  java.math.BigDecimal omtBalance;
    
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