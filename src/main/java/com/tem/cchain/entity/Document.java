package com.tem.cchain.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Document {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String titleCn;
    
    @Column(columnDefinition = "TEXT")
    private String contentCn; // 👈 HTML에서 document.contentCn으로 불러야 합니다!
    
    private String sourceName;
    private String summary; 
    private String createdAt; 
    
    @ManyToOne
    @JoinColumn(name = "member_email") 
    private Member member; 
    
    @Builder.Default 
    private String status = "PENDING";
}