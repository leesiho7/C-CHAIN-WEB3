package com.tem.cchain.dto;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data // Getter, Setter, ToString 등을 자동으로 생성해줍니다.
public class MyPageStatsDto {
    private int translatedDocs = 0;
    private int verifiedCases = 0;
    private String rank = "N/A";
    
    // 리스트 타입을 ActivityDto로 유지하거나, Translation 엔티티로 바꿔도 됩니다.
    // 여기서는 ActivityDto를 사용하는 것으로 유지할게요.
    private List<ActivityDto> recentActivities = new ArrayList<>();
}