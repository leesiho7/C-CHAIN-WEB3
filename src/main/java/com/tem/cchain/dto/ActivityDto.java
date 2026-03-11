package com.tem.cchain.dto;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter @Setter
public class ActivityDto {
    private String contentKr;       // 번역 내용
    private String blockchainHash;  // 해시값
    private String rewardAmount;    // 보상 금액 (예: "+10 C")
    private LocalDateTime verifiedAt; // 승인 시간
}