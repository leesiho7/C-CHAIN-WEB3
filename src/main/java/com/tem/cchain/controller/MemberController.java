package com.tem.cchain.controller;

import java.util.Optional;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import com.tem.cchain.entity.Member;
import com.tem.cchain.service.MemberService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;

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

    @PostMapping("/wallet/quick-deposit")
    public String quickDeposit(@RequestParam double amount, HttpSession session) {
        Member loginMember = (Member) session.getAttribute("loginMember");
        
        if(loginMember == null) {
            return "redirect:/login"; 
        }
        
        try {
            boolean success = memberService.autoDeposit(loginMember.getEmail(), amount);
            if(success) return "redirect:/mypage?success";
        } catch (Exception e) {
            e.printStackTrace();
            return "redirect:/mypage?error";
        }
          
        return "redirect:/mypage?fail";
    }
}