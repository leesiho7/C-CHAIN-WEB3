package com.tem.cchain.config; // 패키지 경로를 확인하세요!

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 세션을 가져옵니다.
        HttpSession session = request.getSession();
        
        // 2. 세션에 로그인 정보가 있는지 확인합니다.
        if (session.getAttribute("loginMember") == null) {
            // 3. 로그인이 안 되어 있다면 로그인 페이지로 강제 이동!
            response.sendRedirect("/login");
            return false; // 더 이상 진행하지 마!
        }
        
        return true; // 로그인 되어 있으면 통과~!
    }
}