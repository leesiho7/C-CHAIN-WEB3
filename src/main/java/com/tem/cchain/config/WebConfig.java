// src/main/java/com/tem/cchain/config/WebConfig.java
package com.tem.cchain.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final LoginInterceptor loginInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/**") // 모든 경로 보호
                .excludePathPatterns("/", "/login", "/join", "/css/**", "/js/**", "/img/**"); // 로그인은 제외
               
        // 💡 만약 관리자 콘솔이 계속 튕기면 .excludePathPatterns("/admin/**")를 임시로 추가해서 테스트해 봐.
    }
}