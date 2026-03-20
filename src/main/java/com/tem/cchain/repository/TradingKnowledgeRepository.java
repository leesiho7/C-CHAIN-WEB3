package com.tem.cchain.repository;

import org.postgresql.util.PGobject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

@Repository
@ConditionalOnProperty(name = "VECTORDB_HOST")
public class TradingKnowledgeRepository {

    @Autowired
    @Qualifier("vectorDbJdbcTemplate")
    private JdbcTemplate jdbcTemplate;

    /**
     * Save trading knowledge with vector embedding to PostgreSQL.
     * Uses PGobject to properly cast the vector data to pgvector type.
     */
    public void saveKnowledge(String text, List<Double> vector) {
        String sql = "INSERT INTO trading_knowledge (strategy_text, strategy_vector) VALUES (?, ?)";
        try {
            PGobject vectorObj = new PGobject();
            vectorObj.setType("vector");
            vectorObj.setValue(vectorToString(vector));

            jdbcTemplate.update(sql, text, vectorObj);
        } catch (SQLException e) {
            System.err.println("지식 저장 실패: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Find similar trading knowledge using pgvector similarity operator (<=>).
     * Uses PGobject to properly cast the query vector to pgvector type.
     */
    public List<String> findSimilarKnowledge(List<Double> queryVector, int limit) {
        String sql = "SELECT strategy_text FROM trading_knowledge ORDER BY strategy_vector <=> ? LIMIT ?";
        try {
            PGobject vectorObj = new PGobject();
            vectorObj.setType("vector");
            vectorObj.setValue(vectorToString(queryVector));

            return jdbcTemplate.queryForList(sql, String.class, vectorObj, limit);
        } catch (SQLException e) {
            System.err.println("유사 지식 검색 실패: " + e.getMessage());
            e.printStackTrace();
            return List.of();
        }
    }

    /**
     * Convert a List<Double> to pgvector string format: "[0.1, 0.2, 0.3, ...]"
     */
    private String vectorToString(List<Double> vector) {
        return vector.stream()
                .map(Object::toString)
                .collect(Collectors.joining(",", "[", "]"));
    }
}
