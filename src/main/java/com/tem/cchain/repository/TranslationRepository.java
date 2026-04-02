package com.tem.cchain.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.tem.cchain.entity.Member;
import com.tem.cchain.entity.Translation;

@Repository
public interface TranslationRepository extends JpaRepository<Translation,Long>{
	
	Optional<Translation> findByBlockchainHash(String blockchainHash);

	//1. 내가 번역한 총 문서 수 (TRANSLATED 12 docs 부분)
	long countByUser(Member user);
	
	//2. 내가 검증 완료한 총 케이스 (VERIFIED 48 cases 부분)
	//staus 필드가 있다면 활용하고, 없다면 단순히 전체 개수를 보여줄 수도 있습니다
	long countByUserAndVerifiedAtIsNotNull(Member user);
	
	//3.최근 기여 활동 목록 (최신순 5개만)
	List<Translation> findByUserOrderByVerifiedAtDesc(Member user);
	
	//4.관리자용 승인 목록
	@EntityGraph(attributePaths = {"user", "document"})
	List<Translation> findByVerifiedAtIsNull();

	//4-1. 관리자 상세 검수용 (user, document LAZY 방지)
	@EntityGraph(attributePaths = {"user", "document"})
	Optional<Translation> findWithDetailsById(Long id);

	// 5. 특정 문서의 완료된 번역본 가져오기 (가장 최근 검증된 것)
	Optional<Translation> findFirstByDocumentIdAndVerifiedAtIsNotNullOrderByVerifiedAtDesc(Long documentId);
	
}
