package com.tem.cchain.entity;

import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties; // 추가 권장

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Getter
@Setter
public class Translation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(columnDefinition = "TEXT")
    private String contentKr;
    
    @ManyToOne(fetch = FetchType.LAZY) // 성능 최적화를 위해 지연 로딩 권장
    @JoinColumn(name = "doc_id")
    @JsonIgnoreProperties({"translations", "contentCn"}) // 불러올 때 불필요한 필드는 제외하여 무한 루프 방지
    @ToString.Exclude // 무한 루프 방지
    private Document document;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private Member user;
    
    private String blockchainHash;
    private LocalDateTime verifiedAt; 
}