package com.tem.cchain.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Properties;

/**
 * MySQL을 Primary DataSource로 명시 설정.
 *
 * @ConfigurationProperties + @Bean 조합은 Spring Boot 3.2.x에서
 * BeanPostProcessor 처리 중 @Primary 플래그가 손실될 수 있으므로
 * @Value 직접 주입 방식으로 DataSource를 구성한다.
 */
@Configuration
@EnableJpaRepositories(
        basePackages = "com.tem.cchain.repository",
        entityManagerFactoryRef = "mysqlEntityManagerFactory",
        transactionManagerRef = "mysqlTransactionManager"
)
public class MySqlDataSourceConfig {

    @Value("${spring.datasource.url}")
    private String url;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    @Value("${spring.datasource.driver-class-name:com.mysql.cj.jdbc.Driver}")
    private String driverClassName;

    /**
     * MySQL DataSource — @Primary 명시로 Spring이 항상 이 DataSource를 기본으로 사용.
     * HikariDataSource 직접 생성: @ConfigurationProperties 방식 불사용
     * (Spring Boot 3.2.x 에서 @Primary 손실 방지).
     */
    @Primary
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
     * com.tem.cchain.entity 패키지만 스캔 — PostgreSQL에는 JPA 엔티티 없음.
     */
    @Primary
    @Bean("mysqlEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean mysqlEntityManagerFactory(
            @Qualifier("dataSource") DataSource dataSource) {

        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(dataSource);
        em.setPackagesToScan("com.tem.cchain.entity");
        em.setPersistenceUnitName("mysql");

        HibernateJpaVendorAdapter adapter = new HibernateJpaVendorAdapter();
        em.setJpaVendorAdapter(adapter);

        Properties props = new Properties();
        props.put("hibernate.dialect",                    "org.hibernate.dialect.MySQLDialect");
        props.put("hibernate.hbm2ddl.auto",               "update");
        props.put("hibernate.show_sql",                   "false");
        props.put("hibernate.format_sql",                 "false");
        props.put("hibernate.jdbc.batch_size",            "50");
        props.put("hibernate.order_inserts",              "true");
        props.put("hibernate.order_updates",              "true");
        props.put("hibernate.jdbc.batch_versioned_data",  "true");
        em.setJpaProperties(props);

        return em;
    }

    /**
     * MySQL 전용 TransactionManager.
     */
    @Primary
    @Bean("mysqlTransactionManager")
    public PlatformTransactionManager mysqlTransactionManager(
            @Qualifier("mysqlEntityManagerFactory") EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }
}
