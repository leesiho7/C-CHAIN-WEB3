package com.tem.cchain.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.tem.cchain.entity.Member;

public interface MemberRepository extends JpaRepository<Member, String> {

	Optional<Member> findByEmailAndUserpw(String email, String userpw);
	
	boolean existsByUserid(String userid);
	//지갑 주소로 사용자 찾기
	Member findByWalletaddressIgnoreCase(String walletaddress);
	
	//계정삭제시 사용
	void deleteByEmail(String email);
}
