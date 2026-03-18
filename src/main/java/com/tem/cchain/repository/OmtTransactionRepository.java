package com.tem.cchain.repository;

import com.tem.cchain.entity.OmtTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigInteger;
import java.util.List;

@Repository
public interface OmtTransactionRepository extends JpaRepository<OmtTransaction, Long> {

    // 1. 중복 저장 방지 (단건 확인 - 실시간 리스너용)
    boolean existsByTxHash(String txHash);

    // 2. [배치 중복 제거] 해시 목록 중 이미 저장된 것을 한 번에 조회
    List<OmtTransaction> findAllByTxHashIn(List<String> txHashes);

    // 3. 특정 주소(보낸 사람 또는 받는 사람)와 관련된 최근 20건 조회
    List<OmtTransaction> findTop20ByFromAddressOrToAddressOrderByBlockNumberDesc(String fromAddress, String toAddress);

    // 4. [통계용] 오늘 발생한 총 전송 횟수
    @Query("SELECT COUNT(t) FROM OmtTransaction t WHERE t.createdAt >= CURRENT_DATE")
    long countTodayTransactions();

    // 5. [통계용] 특정 주소의 누적 전송량 합계
    @Query("SELECT SUM(t.value) FROM OmtTransaction t WHERE t.fromAddress = :address")
    BigInteger sumValueByFromAddress(@Param("address") String address);
}
