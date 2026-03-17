package com.tem.cchain.entity;

import jakarta.persistence.*;
import lombok.*;
import com.fasterxml.jackson.annotation.JsonIgnore; // 추가 필요
import java.util.ArrayList;
import java.util.List;

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
    private String contentCn; 
    
    private String sourceName;
    private String summary; 
    private String createdAt; 
    
    @ManyToOne
    @JoinColumn(name = "member_email") 
    private Member member; 
    
    @Builder.Default 
    private String status = "PENDING";

    @Builder.Default
    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true)
    @ToString.Exclude
    @JsonIgnore // 👈 핵심: JSON 요청/응답 시 이 필드를 무시하게 하여 400 에러를 방지합니다.
    private List<Translation> translations = new ArrayList<>();
}