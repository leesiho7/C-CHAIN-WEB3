package com.tem.cchain.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        try {
            Config config = new Config();
            String address = "redis://" + redisHost + ":" + redisPort;
            
            config.useSingleServer()
                  .setAddress(address)
                  .setConnectionMinimumIdleSize(1)
                  .setConnectTimeout(3000) // 연결 타임아웃 3초
                  .setRetryAttempts(1);    // 재시도 1번만

            if (redisPassword != null && !redisPassword.isEmpty()) {
                config.useSingleServer().setPassword(redisPassword);
            }

            RedissonClient client = Redisson.create(config);
            log.info("✅ Redis 연결 성공: {}:{}", redisHost, redisPort);
            return client;
        } catch (Exception e) {
            log.warn("⚠️ Redis 연결 실패 (로컬 개발 환경일 수 있음): {}", e.getMessage());
            // 빈을 생성하되, 실제 동작 시에는 에러가 날 수 있음을 인지
            return null; 
        }
    }
}
