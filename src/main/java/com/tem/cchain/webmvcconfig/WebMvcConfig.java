package com.tem.cchain.webmvcconfig;

import com.tem.cchain.config.LoginInterceptor; // 패키지 경로가 맞는지 꼭 확인!
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    // 정식으로 경비병을 소환합니다.
    private final LoginInterceptor loginInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/**") // 모든 곳을 검사하되
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
