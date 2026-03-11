package com.tem.cchain.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.tem.cchain.entity.Document;

@Repository
public interface DocumentRepository extends JpaRepository<Document, Long> {
    
    // ID를 기준으로 내림차순(최신순) 정렬하여 모든 문서를 가져오는 메서드 선언
    List<Document> findAllByOrderByIdDesc();
}