package com.tem.cchain.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Repository
public class TradingKnowledgeRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 1. 트레이딩 지식 저장 (텍스트 + 벡터)
     */
    public void saveKnowledge(String text, List<Double> vector) {
        // List<Double>을 pgvector가 인식하는 "[0.1,0.2,...]" 문자열 형식으로 변환
        String vectorString = vector.stream()
                .map(Object::toString)
                .collect(Collectors.joining(",", "[", "]"));

        String sql = "INSERT INTO trading_knowledge (strategy_text, strategy_vector) VALUES (?, ?::vector)";
        
        try {
            jdbcTemplate.update(sql, text, vectorString);
            System.out.println("✅ 지식 저장 완료: " + text.substring(0, Math.min(text.length(), 20)) + "...");
        } catch (Exception e) {
            System.err.println("❌ 지식 저장 실패: " + e.getMessage());
        }
    }

    /**
     * 2. 유사도 검색 (사용자 질문 벡터와 가장 가까운 전략 3개 찾기)
     * <=> 연산자는 코사인 거리를 계산합니다. (값이 작을수록 유사함)
     */
    public List<String> findSimilarKnowledge(List<Double> queryVector, int limit) {
        String vectorString = queryVector.stream()
                .map(Object::toString)
                .collect(Collectors.joining(",", "[", "]"));

        // 코사인 유사도 기반 거리 검색 SQL
        String sql = "SELECT strategy_text FROM trading_knowledge " +
                     "ORDER BY strategy_vector <=> ?::vector " +
                     "LIMIT ?";

        return jdbcTemplate.queryForList(sql, String.class, vectorString, limit);
    }
}