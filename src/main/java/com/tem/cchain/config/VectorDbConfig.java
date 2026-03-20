package com.tem.cchain.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
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

    @Value("${VECTORDB_DATABASE:vectordb}")
    private String database;

    @Value("${VECTORDB_USER:postgres}")
    private String user;

    @Value("${VECTORDB_PASSWORD:}")
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
     * PostgreSQL 전용 Flyway 빈.
     * @Lazy(false): spring.main.lazy-initialization=true 환경에서도 앱 시작 시 즉시 생성,
     *              migrate()가 반드시 실행되어 trading_knowledge 테이블이 생성됨.
     * @Qualifier: @Primary MySQL DataSource 대신 vectorDataSource를 명시 주입.
     */
    @Bean
    @Lazy(false)
    public Flyway flyway(@Qualifier("vectorDataSource") DataSource vectorDataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(vectorDataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .outOfOrder(true)
                .load();
        flyway.migrate();
        return flyway;
    }

    @Bean
    public JdbcTemplate vectorDbJdbcTemplate() {
        return new JdbcTemplate(vectorDataSource());
    }
}
