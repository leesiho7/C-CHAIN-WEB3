package com.tem.cchain.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigInteger;
import java.time.LocalDateTime;

@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OmtTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String txHash; // 트랜잭션 해시 (중복 저장 방지용)

    @Column(nullable = false)
    private Long blockNumber; // 블록 번호 (조회 최적화용 인덱스 권장)

    @Column(name = "from_address", nullable = false)
    private String fromAddress; // 보낸 사람 주소

    @Column(name = "to_address", nullable = false)
    private String toAddress; // 받는 사람 주소

    @Column(precision = 38, scale = 0)
    private BigInteger value; // 전송 수량 (Wei 단위이므로 BigInteger 사용)

    private String status; // SUCCESS, PENDING, FAIL 등 상태값
    
    private LocalDateTime createdAt; // DB 저장 시간

    private String tokenSymbol; // OMT, LINK, USDC 등을 구분하기 위함
}