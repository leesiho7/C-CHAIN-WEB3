package com.tem.cchain.controller;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import com.tem.cchain.entity.Member;
import com.tem.cchain.repository.MemberRepository;
import com.tem.cchain.service.TokenService;

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
@CrossOrigin(origins = "*") 
public class TokenController {

    private final TokenService tokenService;
    private final MemberRepository memberRepository; 

    /**
     * 1. 지갑 연결 페이지 이동
     */
    @GetMapping("/connect")
    public String connectPage() {
        return "metamask";
    }

    /**
     * 2. 지갑 정보 및 잔액 조회 화면 (Thymeleaf 렌더링용)
     */
    @GetMapping("/wallet")
    public String viewWallet(HttpSession session, Model model) {
        String userAddress = (String) session.getAttribute("userAddress");
        
        if(userAddress == null) {
            return "redirect:/connect";
        }

        try {
            // [개선] 직접 호출 대신 DB 정보를 먼저 가져옵니다.
            Member member = memberRepository.findByWalletaddressIgnoreCase(userAddress);
            
            // 실시간 잔액 동기화는 비동기로 던져서 로딩 지연을 방지합니다. (빨간줄 해결 지점 1)
            tokenService.syncBalanceAsync(userAddress);
            
            model.addAttribute("userAddress", userAddress);
            
            // DB에 저장된 잔액을 먼저 보여줌 (사용자 대기 시간 0)
            BigDecimal displayBalance = (member != null && member.getOmtBalance() != null) 
                                        ? member.getOmtBalance() 
                                        : BigDecimal.ZERO;
            
            model.addAttribute("displayBalance", displayBalance);

        } catch (Exception e) {
            e.printStackTrace();
            model.addAttribute("error" , "지갑 정보를 불러오는 중 오류가 발생했습니다.");
        }
        return "wallet"; 
    }

    /**
     * 3. 메타마스크 서명 인증 후 호출 API
     */
    @PostMapping("/api/token/balance")
    @ResponseBody 
    public Map<String, Object> getBalanceApi(@RequestBody Map<String, String> payload, HttpSession session) {
        String address = payload.get("walletAddress");
        Map<String, Object> response = new HashMap<>();
        
        try {
            Member member = memberRepository.findByWalletaddressIgnoreCase(address);
            
            if(member != null) {
                // [개선] 비동기 동기화 호출 (빨간줄 해결 지점 2)
                tokenService.syncBalanceAsync(address);
                
                session.setAttribute("loginMember", member); 
                session.setAttribute("userAddress", address);
                
                response.put("success", true);
                response.put("message", "지갑 인증 성공");
                // API 응답도 DB의 현재 값을 보냄
                response.put("balance", member.getOmtBalance().toString()); 
            } else {
                response.put("success", false);
                response.put("message", "등록된 회원이 아닙니다.");
            }
        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", false);
            response.put("message", "서버 에러: " + e.getMessage());
        }
        
        return response;
    }

    /**
     * 4. 토큰 전송 테스트 API (Sepolia)
     * 실제 운영 시에는 삭제하거나 권한 체크가 필요합니다.
     */
    @GetMapping("/transfer-test")
    @ResponseBody
    public String transferTest(String toAddress) {
        try {
            if (toAddress == null || toAddress.isEmpty()) {
                return "수신 주소를 입력해주세요 (예: /transfer-test?toAddress=0x...)";
            }
            String txHash = tokenService.transferFromMaster(toAddress, 10L);
            return "전송 요청 성공! 해시: " + txHash;
        } catch (Exception e) {
            return "전송 실패: " + e.getMessage();
        }
    }
}