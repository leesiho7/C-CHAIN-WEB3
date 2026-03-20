package com.tem.cchain.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.Database;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Properties;

/**
 * MySQL Primary DataSource + JPA EntityManagerFactory 설정.
 *
 * Dialect 전략:
 *   HibernateJpaVendorAdapter.setDatabase(Database.MYSQL)          → Spring ORM MySQL 지정
 *   HibernateJpaVendorAdapter.setDatabasePlatform(MySQLDialect)    → Hibernate 6.x 명시
 *   Properties에서 hibernate.dialect 제거                           → adapter 단독 제어, 이중 설정 충돌 방지
 *
 * VectorDbConfig(PostgreSQL)는 JdbcTemplate + Flyway만 사용하므로
 * Hibernate/JPA Dialect 설정 대상이 아님. 서로 완전히 분리됨.
 *
 * @Lazy(false): spring.main.lazy-initialization=true 환경에서도
 *              MySQL 빈들이 앱 시작 시 즉시 생성되어 dialect가 확정됨.
 */
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
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName(driverClassName);
        return ds;
    }

    /**
     * MySQL 전용 EntityManagerFactory.
     * - setDatabase(MYSQL): Spring ORM 레벨 DB 타입 명시
     * - setDatabasePlatform(MySQLDialect): Hibernate 6.x dialect 명시
     * - Properties에서 hibernate.dialect 제외: adapter 단독 제어로 충돌 방지
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

        // Dialect: adapter에서 단일 지점으로 관리
        HibernateJpaVendorAdapter adapter = new HibernateJpaVendorAdapter();
        adapter.setDatabase(Database.MYSQL);                                       // Spring ORM
        adapter.setDatabasePlatform("org.hibernate.dialect.MySQLDialect");        // Hibernate 6.x
        adapter.setShowSql(false);
        em.setJpaVendorAdapter(adapter);

        // hibernate.dialect 미포함 — adapter에서 이미 설정, 중복 없음
        Properties props = new Properties();
        props.put("hibernate.hbm2ddl.auto",              "update");
        props.put("hibernate.jdbc.batch_size",           "50");
        props.put("hibernate.order_inserts",             "true");
        props.put("hibernate.order_updates",             "true");
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
