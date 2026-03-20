package com.tem.cchain.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

@Configuration
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

    @Bean
    public DataSource vectorDataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl("jdbc:postgresql://" + host + ":" + port + "/" + database);
        ds.setUsername(user);
        ds.setPassword(password);
        return ds;
    }

    @Bean
    public JdbcTemplate vectorDbJdbcTemplate() {
        return new JdbcTemplate(vectorDataSource());
    }
}
