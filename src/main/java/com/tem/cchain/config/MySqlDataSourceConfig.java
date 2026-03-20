package com.tem.cchain.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Properties;

/**
 * MySQL Primary DataSource + JPA EntityManagerFactory 설정.
 *
 * Dialect 전략:
 *   Properties에서 hibernate.dialect 단일 지점 설정 (Hibernate 6.x 명시)
 *   HibernateJpaVendorAdapter.setDatabasePlatform() 미사용 — Properties와 중복 방지
 *
 * VectorDbConfig(PostgreSQL)는 JdbcTemplate + Flyway만 사용하므로
 * Hibernate/JPA Dialect 설정 대상이 아님. 서로 완전히 분리됨.
 *
 * @Lazy(false): spring.main.lazy-initialization=true 환경에서도
 *              MySQL 빈들이 앱 시작 시 즉시 생성되어 dialect가 확정됨.
 */
@Slf4j
@Configuration
@EnableJpaRepositories(
        basePackages = "com.tem.cchain.repository",
        entityManagerFactoryRef = "mysqlEntityManagerFactory",
        transactionManagerRef = "mysqlTransactionManager"
)
public class MySqlDataSourceConfig {

    // ── application.properties → Railway 환경변수 해석 순서 ──────────────────
    // spring.datasource.url      = jdbc:mysql://${MYSQLHOST}:${MYSQLPORT}/...
    // spring.datasource.username = ${MYSQLUSER}
    // spring.datasource.password = ${MYSQLPASSWORD}
    // spring.datasource.driver-class-name = com.mysql.cj.jdbc.Driver
    // ─────────────────────────────────────────────────────────────────────────
    @Value("${spring.datasource.url}")
    private String url;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    @Value("${spring.datasource.driver-class-name:com.mysql.cj.jdbc.Driver}")
    private String driverClassName;

    /**
     * MySQL @Primary DataSource.
     * HikariDataSource 직접 생성(@ConfigurationProperties 미사용) →
     *   Spring Boot 3.2.x BeanPostProcessor의 @Primary 손실 방지.
     */
    @Primary
    @Lazy(false)
    @Bean("dataSource")
    public DataSource dataSource() {
        // Railway 로그에서 실제 연결 DB 확인용 — 민감정보(password) 제외
        log.info("[MySQL] 연결 URL: {}", url);
        log.info("[MySQL] 연결 USER: {}", username);
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName(driverClassName);
        return ds;
    }

    /**
     * MySQL 전용 EntityManagerFactory.
     * - hibernate.dialect: Properties에서 단일 지점 설정 (adapter.setDatabasePlatform 미사용)
     *   → Spring 6.1.x + Hibernate 6.4.x에서 setDatabase()/setDatabasePlatform() 혼용 시
     *     발생하는 NoSuchMethodError(내부 API 변경) 방지
     * - com.tem.cchain.entity 패키지만 스캔 (PostgreSQL 엔티티 없음)
     */
    @Primary
    @Lazy(false)
    @Bean("mysqlEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean mysqlEntityManagerFactory(
            @Qualifier("dataSource") DataSource dataSource) {

        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(dataSource);
        em.setPackagesToScan("com.tem.cchain.entity");
        em.setPersistenceUnitName("mysql");

        HibernateJpaVendorAdapter adapter = new HibernateJpaVendorAdapter();
        adapter.setShowSql(false);
        em.setJpaVendorAdapter(adapter);

        Properties props = new Properties();
        props.put("hibernate.dialect",               "org.hibernate.dialect.MySQLDialect");
        props.put("hibernate.hbm2ddl.auto",          "update");
        // ── 네이밍 전략: Spring Boot auto-config와 동일하게 명시 ─────────────────
        // 미설정 시 Hibernate 6.x 기본값(CamelCaseToUnderscoresNamingStrategy)이 적용되어
        // 기존 Spring Boot가 생성한 테이블명/컬럼명과 불일치할 수 있음.
        props.put("hibernate.physical_naming_strategy",
                  "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        props.put("hibernate.implicit_naming_strategy",
                  "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy");
        // ── SQL 로그 (Railway 로그에서 실제 쿼리 확인용) ─────────────────────────
        props.put("hibernate.show_sql",              "true");
        props.put("hibernate.format_sql",            "false");
        // ── 배치 최적화 ──────────────────────────────────────────────────────────
        props.put("hibernate.jdbc.batch_size",       "50");
        props.put("hibernate.order_inserts",         "true");
        props.put("hibernate.order_updates",         "true");
        props.put("hibernate.jdbc.batch_versioned_data", "true");
        em.setJpaProperties(props);

        return em;
    }

    /**
     * MySQL 전용 TransactionManager.
     */
    @Primary
    @Lazy(false)
    @Bean("mysqlTransactionManager")
    public PlatformTransactionManager mysqlTransactionManager(
            @Qualifier("mysqlEntityManagerFactory") EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }
}
