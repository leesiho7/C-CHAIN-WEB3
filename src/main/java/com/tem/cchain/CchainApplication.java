package com.tem.cchain;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * C-Chain Main Application
 * 1. @SpringBootApplication: 스프링 부트의 자동 설정, 컴포넌트 스캔 등을 활성화합니다.
 * 2. @EnableAsync: AI 분석 및 블록체인 통신 시 메인 스레드가 대기하지 않도록 비동기 처리를 활성화합니다.
 */
@EnableAsync
@SpringBootApplication
public class CchainApplication {

    public static void main(String[] args) {
        // 프로젝트 실행!
        SpringApplication.run(CchainApplication.class, args);
    }

}