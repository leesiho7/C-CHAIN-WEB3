package com.tem.cchain.controller;

import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import com.tem.cchain.entity.Member;
import com.tem.cchain.service.MemberService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class MemberController {
    
    private final MemberService memberService;
    
    // [변경] 첫 접속 시 화려한 인트로 페이지를 보여줍니다.
    @GetMapping("/")
    public String index() {
        return "intro"; 
    }

    @GetMapping("/join")
    public String joinForm() {
        return "join"; 
    }
    
    @PostMapping("/join")
    public String join(Member member, Model model) {
        try {
            memberService.register(member);
            return "redirect:/login?registered";
        } catch (Exception e) {
            model.addAttribute("errorMsg", "회원가입 중 오류가 발생했습니다: " + e.getMessage());
            return "join";
        }
    }

    @GetMapping("/login")
    public String loginForm() {
        return "login";
    }

    @PostMapping("/login")
    public String login(@RequestParam String email,
                        @RequestParam String userpw,
                        HttpSession session,
                        Model model) {
        try {
            Optional<Member> member = memberService.login(email, userpw);
            if (member.isPresent()) {
                session.setAttribute("loginMember", member.get());
                return "redirect:/main";
            } else {
                model.addAttribute("loginError", "이메일 또는 비밀번호가 올바르지 않습니다.");
                return "login";
            }
        } catch (Exception e) {
            model.addAttribute("loginError", "로그인 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.");
            return "login";
        }
    }

    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/main";
    }

    /**
     * MetaMask 지갑 주소 연동 API.
     *
     * 프론트엔드에서 MetaMask 연결 후 이 엔드포인트로 주소를 전달합니다.
     * 실제 운영에서는 MetaMask 서명 검증(personal_sign)을 추가해야 합니다.
     *
     * 호출 예시 (JavaScript):
     *   const accounts = await window.ethereum.request({ method: 'eth_requestAccounts' });
     *   await fetch('/wallet/connect', {
     *     method: 'POST',
     *     headers: { 'Content-Type': 'application/json' },
     *     body: JSON.stringify({ walletAddress: accounts[0] })
     *   });
     */
    @PostMapping("/wallet/connect")
    @ResponseBody
    public ResponseEntity<Map<String, String>> connectWallet(
            @RequestBody Map<String, String> body,
            HttpSession session) {

        Member loginMember = (Member) session.getAttribute("loginMember");
        if (loginMember == null) {
            return ResponseEntity.status(401).body(Map.of("error", "로그인이 필요합니다."));
        }

        String walletAddress = body.get("walletAddress");
        try {
            memberService.connectWallet(loginMember.getEmail(), walletAddress);
            loginMember.setWalletaddress(walletAddress);  // 세션도 갱신
            session.setAttribute("loginMember", loginMember);
            return ResponseEntity.ok(Map.of("address", walletAddress, "status", "연동 완료"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/wallet/quick-deposit")
    public String quickDeposit(@RequestParam double amount, HttpSession session) {
        Member loginMember = (Member) session.getAttribute("loginMember");
        if (loginMember == null) return "redirect:/login";
        try {
            boolean success = memberService.autoDeposit(loginMember.getEmail(), amount);
            if (success) return "redirect:/mypage?success";
        } catch (Exception e) {
            return "redirect:/mypage?error";
        }
        return "redirect:/mypage?fail";
    }
}