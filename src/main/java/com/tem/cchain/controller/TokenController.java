package com.tem.cchain.controller;

import java.math.BigDecimal;
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
import com.tem.cchain.service.WalletService; // WalletService 임포트 확인

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
@CrossOrigin(origins = "*") 
public class TokenController {

    private final TokenService tokenService;
    private final MemberRepository memberRepository; 
    private final WalletService walletService; // 인스턴스 메서드를 사용한다면 주입 유지

    @GetMapping("/connect")
    public String connectPage() {
        return "metamask";
    }

    @GetMapping("/wallet-dashboard")
    public String dashboardPage(HttpSession session) {
        if (session.getAttribute("userAddress") == null) {
            return "redirect:/connect";
        }
        return "wallet-dashboard";
    }

    @GetMapping("/wallet")
    public String viewWallet(HttpSession session, Model model) {
        String userAddress = (String) session.getAttribute("userAddress");
        if(userAddress == null) return "redirect:/connect";

        try {
            Member member = memberRepository.findByWalletaddressIgnoreCase(userAddress);
            // 비동기로 잔액 동기화 시도
            tokenService.syncBalanceAsync(userAddress);
            
            model.addAttribute("userAddress", userAddress);
            
            // [방어 코드] DB 정보가 없거나 잔액이 null이면 0으로 표시
            BigDecimal displayBalance = (member != null && member.getOmtBalance() != null) 
                                        ? member.getOmtBalance() 
                                        : BigDecimal.ZERO;
            
            model.addAttribute("displayBalance", displayBalance);
        } catch (Exception e) {
            model.addAttribute("error" , "지갑 정보를 불러오는 중 오류가 발생했습니다.");
        }
        return "wallet"; 
    }

    /**
     * 로그인된 회원의 walletaddress를 MetaMask 주소로 연결(덮어쓰기)
     */
    @PostMapping("/api/wallet/link")
    @ResponseBody
    public Map<String, Object> linkWallet(@RequestBody Map<String, String> payload, HttpSession session) {
        Map<String, Object> response = new HashMap<>();
        Member loginMember = (Member) session.getAttribute("loginMember");

        if (loginMember == null) {
            response.put("success", false);
            response.put("message", "로그인이 필요합니다. 먼저 로그인해주세요.");
            return response;
        }

        String metaMaskAddress = payload.get("walletAddress");
        if (metaMaskAddress == null || metaMaskAddress.isBlank()) {
            response.put("success", false);
            response.put("message", "MetaMask 주소가 없습니다.");
            return response;
        }

        // DB 업데이트를 위해 리포지토리에서 실제 영속화된 객체를 가져오는 것을 권장
        try {
            Member member = memberRepository.findById(loginMember.getId()).orElse(loginMember);
            member.setWalletaddress(metaMaskAddress);
            memberRepository.save(member);

            // 세션 최신화
            session.setAttribute("loginMember", member);
            session.setAttribute("userAddress", metaMaskAddress);

            tokenService.syncBalanceAsync(metaMaskAddress);

            response.put("success", true);
            response.put("message", "지갑 연결 성공");
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "DB 저장 중 오류: " + e.getMessage());
        }
        
        return response;
    }

    @PostMapping("/api/token/balance")
    @ResponseBody
    public Map<String, Object> getBalanceApi(HttpSession session) {
        String address = (String) session.getAttribute("userAddress");
        Map<String, Object> response = new HashMap<>();

        try {
            double realBalance = walletService.getOmtBalance(address);
            
            // 백그라운드에서 DB 동기화 실행
            tokenService.syncBalanceAsync(address);

            response.put("success", true);
            response.put("message", "잔액 조회 성공");
            response.put("balance", String.format("%.2f", realBalance));

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "조회 실패: " + e.getMessage());
        }
        return response;
    }

    @GetMapping("/transfer-test")
    @ResponseBody
    public String transferTest(String toAddress) {
        try {
            if (toAddress == null || toAddress.isEmpty()) {
                return "수신 주소를 입력해주세요";
            }
            String txHash = tokenService.transferFromMaster(toAddress, 10L);
            return "전송 요청 성공! 해시: " + txHash;
        } catch (Exception e) {
            return "전송 실패: " + e.getMessage();
        }
    }
}