package com.tem.cchain.controller;

import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.tem.cchain.entity.Document;
import com.tem.cchain.repository.DocumentRepository;

import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
public class MainController {
	
	private final DocumentRepository documentRepository;
	
	@GetMapping("/main")
	public String mainPage(Model model) {
		//1.DB에서 모든 문서 가져오기
		List<Document>documents = documentRepository.findAllByOrderByIdDesc();
	    
		//2. 화면(HTML)으로 문서 리스트 전달하기
		model.addAttribute("documents", documents);
		
		return "main"; // main.html 실행
	}
	
	@GetMapping("/wallet-dashboard")
	public String walletReport() {
	    // 반드시 src/main/resources/templates 안에 
	    // wallet-report.html 파일이 있어야 합니다.
	    return "wallet-dashboard"; 
	}
}
