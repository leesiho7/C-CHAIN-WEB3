package com.tem.cchain.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.stream.Collectors;

@Repository
@ConditionalOnProperty(name = "VECTORDB_HOST")
public class TradingKnowledgeRepository {

    @Autowired
    @Qualifier("vectorDbJdbcTemplate")
    private JdbcTemplate jdbcTemplate;

    /**
     * INSERT 시 ?::vector 캐스트를 SQL에 직접 명시.
     * PGobject 방식은 JDBC 드라이버가 vector 타입을 double precision으로 잘못 해석할 수 있어
     * PostgreSQL 네이티브 캐스트(::<type>)를 사용하는 것이 가장 확실한 방법.
     */
    public void saveKnowledge(String text, List<Double> vector) {
        String sql = "INSERT INTO trading_knowledge (strategy_text, strategy_vector) VALUES (?, ?::vector)";
        try {
            jdbcTemplate.update(sql, text, vectorToString(vector));
        } catch (Exception e) {
            System.err.println("지식 저장 실패: " + e.getMessage());
        }
    }

    /**
     * 유사도 검색 시에도 ?::vector 캐스트 명시.
     * <=> 연산자는 pgvector의 코사인 거리 연산자.
     */
    public List<String> findSimilarKnowledge(List<Double> queryVector, int limit) {
        String sql = "SELECT strategy_text FROM trading_knowledge ORDER BY strategy_vector <=> ?::vector LIMIT ?";
        try {
            return jdbcTemplate.queryForList(sql, String.class, vectorToString(queryVector), limit);
        } catch (Exception e) {
            System.err.println("유사 지식 검색 실패: " + e.getMessage());
            return List.of();
        }
    }

    private String vectorToString(List<Double> vector) {
        return vector.stream()
                .map(Object::toString)
                .collect(Collectors.joining(",", "[", "]"));
    }
}
