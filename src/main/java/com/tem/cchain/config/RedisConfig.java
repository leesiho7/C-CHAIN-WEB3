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

import java.net.URI;

/**
 * Redisson 클라이언트 설정.
 *
 * 환경변수 우선순위:
 *   1. REDIS_URL (Railway 권장: redis://default:password@host:port)
 *      → host / port / password 를 URL에서 파싱
 *   2. 개별 변수: REDIS_HOST(또는 REDISHOST) / REDIS_PORT(또는 REDISPORT) / REDIS_PASSWORD
 *      → application.properties에서 spring.data.redis.* 로 매핑됨
 *   3. localhost:6379 — 로컬 개발 전용 fallback
 *
 * Railway 설정 시 Redis 서비스 → 앱 서비스에 REDIS_URL 변수를 연결(Reference Variable)할 것.
 */
@Slf4j
@Configuration
public class RedisConfig {

    /** Railway Redis 서비스가 제공하는 전체 URL (redis://[user:password@]host:port) */
    @Value("${REDIS_URL:}")
    private String redisUrl;

    /** 개별 변수 fallback — application.properties에서 REDIS_HOST / REDISHOST 순으로 시도 */
    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    /**
     * @Lazy(false): spring.main.lazy-initialization=true 환경에서도 앱 시작 시 즉시 연결.
     * useSingleServer() 단일 체인: 두 번 호출하면 두 번째 호출이 첫 번째 설정을 참조 못할 수 있음.
     */
    @Lazy(false)
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        String host = redisHost;
        int    port = redisPort;
        String password = redisPassword;

        // ── 1순위: REDIS_URL 파싱 ──────────────────────────────────────────────
        if (redisUrl != null && !redisUrl.isBlank()) {
            try {
                URI uri = URI.create(redisUrl);
                host = uri.getHost();
                port = uri.getPort() > 0 ? uri.getPort() : 6379;

                // userInfo 형식: "default:password" 또는 ":password"
                String userInfo = uri.getUserInfo();
                if (userInfo != null && userInfo.contains(":")) {
                    password = userInfo.split(":", 2)[1];
                } else if (userInfo != null && !userInfo.isBlank()) {
                    password = userInfo;
                }
                log.info("[Redis] REDIS_URL 파싱 → {}:{}", host, port);
            } catch (Exception e) {
                log.warn("[Redis] REDIS_URL 파싱 실패, 개별 환경변수로 fallback: {}", e.getMessage());
            }
        } else {
            // ── 2순위: 개별 변수 (이미 spring.data.redis.host 에 주입됨) ────────
            log.info("[Redis] 개별 환경변수 사용 → {}:{}", host, port);
        }

        Config config = new Config();
        SingleServerConfig server = config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(4)
                .setConnectTimeout(5000)
                .setRetryAttempts(3)
                .setRetryInterval(1500);

        if (password != null && !password.isEmpty()) {
            server.setPassword(password);
        }

        log.info("[Redis] 연결 주소: redis://{}:{}", host, port);
        return Redisson.create(config);
    }
}
