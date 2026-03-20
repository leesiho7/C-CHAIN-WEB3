package com.tem.cchain.webmvcconfig;

import com.tem.cchain.config.LoginInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 인터셉터 설정.
 * CORS는 SecurityConfig(Spring Security 레벨)에서 단일 관리.
 * MVC addCorsMappings는 Security CORS보다 나중에 적용되어 중복/충돌 방지를 위해 제거.
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final LoginInterceptor loginInterceptor;

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
