package com.tem.cchain.service;

import org.springframework.stereotype.Service;
import com.tem.cchain.dto.MyPageStatsDto; // 별도로 만든 DTO를 임포트
import com.tem.cchain.repository.TranslationRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MyPageService {

    private final TranslationRepository translationRepo;
    // 만약 활동 내역 조회를 위해 다른 리포지토리가 필요하다면 추가
    // private final ActivityRepository activityRepo; 

    public MyPageStatsDto getStats(String userAddress) {
        MyPageStatsDto stats = new MyPageStatsDto();
        
        try {
            // 1. 번역한 문서 개수 조회 (예시)
            // long count = translationRepo.countByUserAddress(userAddress);
            // stats.setTranslatedDocs((int) count);

            // 2. 검증 완료 건수 조회 (예시)
            // stats.setVerifiedCases(5); 

            // 3. 활동 내역 리스트 조회 (중요: DTO 내의 recentActivities 리스트 채우기)
            // List<ActivityDto> activities = activityRepo.findTop5ByUserAddress(userAddress);
            // stats.setRecentActivities(activities);

            stats.setRank("TOP 5%"); // 예시 데이터
            
        } catch (Exception e) {
            // 조회 중 에러가 나도 빈 객체는 유지되도록 처리
            e.printStackTrace();
        }
        
        return stats; 
    }
}