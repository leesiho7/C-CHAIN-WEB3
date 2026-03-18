package com.tem.cchain.repository;

import com.tem.cchain.entity.IndexerState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IndexerStateRepository extends JpaRepository<IndexerState, String> {
    // 기본 상속 메소드(findById, save)만으로 IndexerState 관리가 가능합니다.
}