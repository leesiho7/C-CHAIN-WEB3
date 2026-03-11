package com.tem.cchain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TranslationDto {
    private String documentTitle;   // 번역 문서 제목
    private String contentKr;       // 번역된 한국어 내용
    private String blockchainHash;  // 생성된 실시간 해시값
    private String documentId;      // 원본 문서 ID (String으로 받아도 컨트롤러에서 Long으로 변환 가능)
}