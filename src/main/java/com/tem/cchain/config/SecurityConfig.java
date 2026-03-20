package com.tem.cchain.config;

import com.tem.cchain.service.CustomUserDetailsService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security 설정.
 *
 * 역할 분담:
 *   - SecurityConfig : CSRF 비활성화 / CORS 필터 / Spring Security 필터 체인 관리
 *   - LoginInterceptor: 세션 기반 로그인 여부 확인 (기존 방식 유지)
 *   - MemberController : 로그인/회원가입 폼 처리 (기존 방식 유지)
 *
 * 의존성 순서:
 *   SecurityConfig → CustomUserDetailsService → MemberRepository
 *                 → mysqlEntityManagerFactory → dataSource (@Lazy(false))
 *   @DependsOn("dataSource")으로 MySQL DataSource가 먼저 준비됨을 보장.
 *
 * 비밀번호:
 *   현재 DB에 평문 저장 → NoOpPasswordEncoder 사용.
 *   향후 BCrypt로 전환 시 PasswordEncoder Bean만 교체하면 됨.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@DependsOn({"dataSource", "mysqlEntityManagerFactory"})
public class SecurityConfig {

    private final CustomUserDetailsService userDetailsService;

    @Value("${RAILWAY_PUBLIC_DOMAIN:}")
    private String railwayDomain;

    /**
     * SecurityFilterChain:
     *   - CSRF 완전 비활성화 → 폼 POST 요청(로그인/회원가입) 403 방지
     *   - CORS는 corsConfigurationSource() 빈으로 처리 (MVC CORS보다 먼저 실행)
     *   - 모든 경로 permitAll → 접근 제어는 LoginInterceptor가 담당
     *   - 기본 httpBasic / formLogin 비활성화 → MemberController가 담당
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable);

        return http.build();
    }

    /**
     * CORS 설정 (Spring Security 레벨 — MVC보다 먼저 적용).
     *
     * 허용 출처:
     *   1. http(s)://localhost:8080  — 로컬 개발
     *   2. https://{RAILWAY_PUBLIC_DOMAIN} — Railway 배포 도메인
     *      (환경변수 RAILWAY_PUBLIC_DOMAIN이 없으면 localhost만 허용)
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        if (railwayDomain != null && !railwayDomain.isBlank()) {
            config.setAllowedOrigins(List.of(
                    "http://localhost:8080",
                    "https://localhost:8080",
                    "https://" + railwayDomain
            ));
        } else {
            config.setAllowedOrigins(List.of(
                    "http://localhost:8080",
                    "https://localhost:8080"
            ));
        }

        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /**
     * PasswordEncoder: 현재 평문 비밀번호 → NoOpPasswordEncoder.
     * Spring Security가 UserDetailsService 연동 시 사용.
     */
    @SuppressWarnings("deprecation")
    @Bean
    public PasswordEncoder passwordEncoder() {
        return NoOpPasswordEncoder.getInstance();
    }
}
