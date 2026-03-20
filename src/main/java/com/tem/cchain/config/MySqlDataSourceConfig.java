package com.tem.cchain.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
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
 * 배경: VectorDbConfig가 PostgreSQL용 DataSource 빈을 등록하면
 * Spring Boot의 DataSourceAutoConfiguration이 @ConditionalOnMissingBean으로 백오프하여
 * MySQL DataSource가 생성되지 않고 JPA가 PostgreSQL에 연결하는 문제가 발생.
 * 이 클래스가 @Primary MySQL DataSource + EntityManagerFactory + TransactionManager를
 * 명시적으로 등록하여 JPA가 항상 MySQL을 사용하도록 보장한다.
 */
@Configuration
@EnableJpaRepositories(
        basePackages = "com.tem.cchain.repository",
        entityManagerFactoryRef = "mysqlEntityManagerFactory",
        transactionManagerRef = "mysqlTransactionManager"
)
public class MySqlDataSourceConfig {

    /**
     * MySQL DataSource — application.properties의 spring.datasource.* 바인딩.
     * @Primary로 설정하여 JPA auto-config 및 다른 빈들이 기본으로 이 DataSource를 사용.
     */
    @Primary
    @Bean("dataSource")
    @ConfigurationProperties(prefix = "spring.datasource")
    public DataSource dataSource() {
        return DataSourceBuilder.create().build();
    }

    /**
     * MySQL 전용 EntityManagerFactory.
     * com.tem.cchain.entity 패키지만 스캔 → PostgreSQL에는 JPA 엔티티 없음.
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
        props.put("hibernate.dialect",                        "org.hibernate.dialect.MySQLDialect");
        props.put("hibernate.hbm2ddl.auto",                   "update");
        props.put("hibernate.show_sql",                       "false");
        props.put("hibernate.format_sql",                     "false");
        props.put("hibernate.jdbc.batch_size",                "50");
        props.put("hibernate.order_inserts",                  "true");
        props.put("hibernate.order_updates",                  "true");
        props.put("hibernate.jdbc.batch_versioned_data",      "true");
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
