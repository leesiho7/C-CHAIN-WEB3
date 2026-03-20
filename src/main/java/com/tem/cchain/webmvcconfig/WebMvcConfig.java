package com.tem.cchain.webmvcconfig;

import com.tem.cchain.config.LoginInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final LoginInterceptor loginInterceptor;

    /**
     * Railway 배포 도메인 — 환경변수 RAILWAY_PUBLIC_DOMAIN으로 주입.
     * 없으면 빈 문자열(로컬 개발 시 localhost만 허용).
     */
    @Value("${RAILWAY_PUBLIC_DOMAIN:}")
    private String railwayDomain;

    /**
     * CORS 허용 출처:
     *   - http(s)://localhost:8080  (로컬 개발)
     *   - https://{RAILWAY_PUBLIC_DOMAIN} (Railway 배포 도메인)
     * /api/** 전체에 적용 — Thymeleaf 템플릿은 같은 서버에서 내려주므로 CORS 불필요.
     * 외부 클라이언트(모바일 앱, 별도 프론트엔드)나 도메인 불일치 상황 대비.
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String railwayOrigin = (railwayDomain != null && !railwayDomain.isBlank())
                ? "https://" + railwayDomain
                : null;

        String[] origins = (railwayOrigin != null)
                ? new String[]{"http://localhost:8080", "https://localhost:8080", railwayOrigin}
                : new String[]{"http://localhost:8080", "https://localhost:8080"};

        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                    "/",
                    "/main",
                    "/indexer",
                    "/exchange",
                    "/price",
                    "/wallet-server",
                    "/login",
                    "/join",
                    "/logout",
                    "/css/**",
                    "/js/**",
                    "/images/**",
                    "/shop",
                    "/api/**"
                );
    }
}
