package com.tem.cchain.controller;

import com.tem.cchain.dto.DashboardSummaryDto;
import com.tem.cchain.entity.Member;
import com.tem.cchain.entity.OmtTransaction;
import com.tem.cchain.repository.OmtTransactionRepository;
import com.tem.cchain.service.DashboardService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final OmtTransactionRepository txRepository;

    /**
     * 대시보드에 필요한 모든 데이터를 한 번에 반환.
     * 세션에서 로그인 회원의 지갑 주소를 꺼내 온체인 잔액 조회에 활용.
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getFullSummary(HttpSession session) {
        Map<String, Object> response = new HashMap<>();

        // 세션에서 로그인 회원 정보 조회
        Member loginMember = (Member) session.getAttribute("loginMember");
        String walletAddress = (loginMember != null) ? loginMember.getWalletaddress() : null;

        // 1. 자산 요약 (총액, ETH/OMT 잔액, 시세)
        DashboardSummaryDto summary = dashboardService.getDashboardSummary(walletAddress);

        // 2. 최근 거래 내역 (최신순 5건)
        List<OmtTransaction> recentTxs = txRepository.findAll(
                PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "blockNumber"))
        ).getContent();

        response.put("summary", summary);
        response.put("recentTransactions", recentTxs);
        response.put("walletAddress", walletAddress != null ? walletAddress : "");

        return ResponseEntity.ok(response);
    }
}
