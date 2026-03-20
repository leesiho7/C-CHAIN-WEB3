package com.tem.cchain.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
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

    /**
     * @Lazy(false): spring.main.lazy-initialization=true 환경에서도 앱 시작 시 즉시 연결.
     * null 반환 제거: Spring 빈이 null이면 주입받는 쪽에서 NullPointerException 발생.
     * useSingleServer() 단일 체인: 두 번 호출하면 두 번째 호출이 첫 번째 설정을 참조 못할 수 있음.
     */
    @Lazy(false)
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();

        SingleServerConfig server = config.useSingleServer()
                .setAddress("redis://" + redisHost + ":" + redisPort)
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(4)
                .setConnectTimeout(5000)
                .setRetryAttempts(3)
                .setRetryInterval(1500);

        if (redisPassword != null && !redisPassword.isEmpty()) {
            server.setPassword(redisPassword);
        }

        log.info("Redis 연결 시도: {}:{}", redisHost, redisPort);
        return Redisson.create(config);
    }
}
