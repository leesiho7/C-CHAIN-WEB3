package com.tem.cchain.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 서버 측 데이터 없이 Thymeleaf 뷰만 반환하는 페이지 라우터.
 * 각 페이지의 실제 데이터는 JS에서 /api/** REST로 직접 호출한다.
 */
@Controller
public class PageController {

    @GetMapping("/indexer")
    public String indexerPage() {
        return "transaction-indexer";
    }

    @GetMapping("/exchange")
    public String exchangePage() {
        return "wallet-exchange";
    }

    @GetMapping("/price")
    public String pricePage() {
        return "price-stream";
    }

    @GetMapping("/wallet-server")
    public String walletServerPage() {
        return "wallet-server";
    }
}
