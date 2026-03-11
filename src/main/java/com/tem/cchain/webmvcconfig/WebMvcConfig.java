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
                    "/login", 
                    "/join", 
                    "/logout",
                    "/css/**", 
                    "/js/**", 
                    "/images/**", // 여기서 줄을 잘 맞춰주세요!
                    "/shop",        // ✨ 드디어 상점 프리패스!
                    "/api/**"       // API 호출도 일단 열어두는게 안전해요
                );
    }
}
