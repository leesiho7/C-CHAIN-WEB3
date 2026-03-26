package com.tem.cchain.repository;

import com.tem.cchain.entity.OmtTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigInteger;
import java.util.List;

@Repository
public interface OmtTransactionRepository extends JpaRepository<OmtTransaction, Long> {

    // 1. 중복 저장 방지
    boolean existsByTxHash(String txHash);

    // 2. 배치 중복 제거용 — IN 쿼리 일괄 조회
    List<OmtTransaction> findAllByTxHashIn(List<String> txHashes);

    // 3. 최근 100건 — 화면 표시용 (50~100 슬라이딩 윈도우를 DB LIMIT으로 처리)
    List<OmtTransaction> findTop100ByOrderByBlockNumberDesc();

    // 4. 특정 주소 최근 50건
    List<OmtTransaction> findTop50ByFromAddressOrToAddressOrderByBlockNumberDesc(
            String fromAddress, String toAddress);

    // 5. 오늘 발생한 총 전송 횟수
    @Query("SELECT COUNT(t) FROM OmtTransaction t WHERE t.createdAt >= CURRENT_DATE")
    long countTodayTransactions();

    // 6. 특정 주소 누적 전송량
    @Query("SELECT SUM(t.value) FROM OmtTransaction t WHERE t.fromAddress = :address")
    BigInteger sumValueByFromAddress(@Param("address") String address);

    // 7. DB 정리 — 가장 오래된 N건 삭제 (blockNumber 오름차순 기준)
    @Modifying
    @Query(value = """
            DELETE FROM omt_transaction
            WHERE id IN (
                SELECT id FROM (
                    SELECT id FROM omt_transaction
                    ORDER BY block_number ASC
                    LIMIT :n
                ) AS sub
            )
            """, nativeQuery = true)
    int deleteOldestN(@Param("n") long n);
}
