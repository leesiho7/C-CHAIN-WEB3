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

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
@CrossOrigin(origins = "*") 
public class TokenController {

    private final TokenService tokenService;
    private final MemberRepository memberRepository; 

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
            tokenService.syncBalanceAsync(userAddress);
            
            model.addAttribute("userAddress", userAddress);
            
            // [방어 코드] 잔액이 null이면 0으로 표시
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

        // DB의 walletaddress를 MetaMask 주소로 업데이트
        loginMember.setWalletaddress(metaMaskAddress);
        memberRepository.save(loginMember);

        // 세션도 최신 상태로 갱신
        session.setAttribute("loginMember", loginMember);
        session.setAttribute("userAddress", metaMaskAddress);

        tokenService.syncBalanceAsync(metaMaskAddress);

        response.put("success", true);
        response.put("message", "지갑 연결 성공");
        return response;
    }

    @PostMapping("/api/token/balance")
    @ResponseBody
    public Map<String, Object> getBalanceApi(@RequestBody Map<String, String> payload, HttpSession session) {
        String address = payload.get("walletAddress");
        Map<String, Object> response = new HashMap<>();

        try {
            Member member = memberRepository.findByWalletaddressIgnoreCase(address);

            if(member != null) {
                tokenService.syncBalanceAsync(address);

                session.setAttribute("loginMember", member);
                session.setAttribute("userAddress", address);

                response.put("success", true);
                response.put("message", "지갑 인증 성공");

                BigDecimal balance = member.getOmtBalance();
                response.put("balance", (balance != null) ? balance.toString() : "0");

            } else {
                response.put("success", false);
                response.put("message", "등록된 회원이 아닙니다.");
            }
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "서버 에러: " + e.getMessage());
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