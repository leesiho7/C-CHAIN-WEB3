package com.tem.cchain.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Repository
@ConditionalOnProperty(name = "VECTORDB_HOST")
public class TradingKnowledgeRepository {

    @Autowired
    @Qualifier("vectorDbJdbcTemplate")
    private JdbcTemplate jdbcTemplate;

    public void saveKnowledge(String text, List<Double> vector) {
        String vectorString = vector.stream()
                .map(Object::toString)
                .collect(Collectors.joining(",", "[", "]"));
        String sql = "INSERT INTO trading_knowledge (strategy_text, strategy_vector) VALUES (?, ?::vector)";
        try {
            jdbcTemplate.update(sql, text, vectorString);
        } catch (Exception e) {
            System.err.println("지식 저장 실패: " + e.getMessage());
        }
    }

    public List<String> findSimilarKnowledge(List<Double> queryVector, int limit) {
        String vectorString = queryVector.stream()
                .map(Object::toString)
                .collect(Collectors.joining(",", "[", "]"));
        String sql = "SELECT strategy_text FROM trading_knowledge ORDER BY strategy_vector <=> ?::vector LIMIT ?";
        return jdbcTemplate.queryForList(sql, String.class, vectorString, limit);
    }
}
