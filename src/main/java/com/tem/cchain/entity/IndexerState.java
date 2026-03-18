package com.tem.cchain.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexerState {
    @Id
    private String serviceName; // "OMT_INDEXER" 등 서비스 이름

    private Long lastBlockNumber; // 마지막으로 성공적으로 처리한 블록 번호
    
    private String network; // "SEPOLIA" 등 네트워크 구분
}