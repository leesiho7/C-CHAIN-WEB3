package com.tem.cchain.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MyPageStatsResponse {
    private long translatedDocs;
    private long verifiedCases;
    private String rank;
    private List<RecentActivityDto> recentActivities; 

    // 타임리프가 'stats.translatedDocs'로 호출할 때 찾는 문입니다.
    public long getTranslatedDocs() { 
        return translatedDocs; 
    }

    public long getVerifiedCases() { 
        return verifiedCases; 
    }

    public String getRank() { 
        return rank; 
    }

    // ⭐ 중요: 타임리프가 'stats.recentActivities'를 찾을 때 이 메서드를 사용합니다.
    public List<RecentActivityDto> getRecentActivities() { 
        return recentActivities; 
    }
}