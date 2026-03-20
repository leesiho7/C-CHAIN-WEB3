package com.tem.cchain.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/**
 * PostgreSQL VectorDB 전용 DataSource 및 Flyway 설정.
 * VECTORDB_HOST 환경변수가 있을 때만 활성화 → primary DB(MongoDB/MySQL) 완전 분리.
 */
@Configuration
@ConditionalOnProperty(name = "VECTORDB_HOST")
public class VectorDbConfig {

    @Value("${VECTORDB_HOST}")
    private String host;

    @Value("${VECTORDB_PORT:5432}")
    private String port;

    @Value("${VECTORDB_DATABASE}")
    private String database;

    @Value("${VECTORDB_USER}")
    private String user;

    @Value("${VECTORDB_PASSWORD}")
    private String password;

    /**
     * PostgreSQL VectorDB DataSource - explicitly configured for Flyway.
     * This is NOT the primary datasource and will not be used by JPA/Hibernate.
     */
    @Bean
    public DataSource vectorDataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl("jdbc:postgresql://" + host + ":" + port + "/" + database);
        ds.setUsername(user);
        ds.setPassword(password);
        return ds;
    }

    /**
     * Explicit Flyway bean that uses vectorDataSource ONLY.
     * This ensures Flyway runs migrations on PostgreSQL, not the default datasource.
     */
    @Bean
    public Flyway flyway(DataSource vectorDataSource) {
        return Flyway.configure()
                .dataSource(vectorDataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .outOfOrder(true)
                .repairOnMigrate(true)
                .load();
    }

    @Bean
    public JdbcTemplate vectorDbJdbcTemplate() {
        return new JdbcTemplate(vectorDataSource());
    }
}
