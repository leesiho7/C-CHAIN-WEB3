package com.tem.cchain.service;

import com.tem.cchain.entity.OmtTransaction;
import com.tem.cchain.repository.OmtTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.protocol.core.methods.response.Log;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 별도 Spring 빈으로 분리해 @Transactional 프록시가 정상 적용되도록 함.
 * SyncService 내부에서 self-call 하면 트랜잭션이 무시되기 때문.
 *
 * ── 추가: @Scheduled DB 정리 잡 ───────────────────────────────
 * 매일 새벽 3시, 전체 행 수가 10,000건을 초과하면
 * 초과분만큼 가장 오래된(blockNumber 기준) 레코드를 일괄 삭제.
 * INSERT마다 삭제하는 슬라이딩 방식 대비 DB I/O를 90% 이상 절감.
 * ──────────────────────────────────────────────────────────────
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndexerBatchSaver {

    private static final long MAX_ROWS = 10_000L;

    private final OmtTransactionRepository txRepository;

    // =========================================================================
    // 배치 저장
    // =========================================================================

    /**
     * 로그 목록을 받아 중복 제거 후 saveAll()로 배치 INSERT.
     */
    @Transactional
    public int saveBatch(List<Log> logs, Map<String, String> watchTargets) {
        if (logs.isEmpty()) return 0;

        List<String> hashes = logs.stream()
                .map(Log::getTransactionHash)
                .collect(Collectors.toList());

        Set<String> existingHashes = txRepository.findAllByTxHashIn(hashes)
                .stream()
                .map(OmtTransaction::getTxHash)
                .collect(Collectors.toSet());

        List<OmtTransaction> toSave = new ArrayList<>();
        for (Log logData : logs) {
            if (existingHashes.contains(logData.getTransactionHash())) continue;
            try {
                toSave.add(parseLog(logData, watchTargets));
            } catch (Exception e) {
                log.warn("[Batch] 파싱 실패 (txHash={}): {}",
                        logData.getTransactionHash(), e.getMessage());
            }
        }

        if (toSave.isEmpty()) return 0;

        txRepository.saveAll(toSave);
        log.info("[Batch] {}건 저장 (중복 {}건 제외)",
                toSave.size(), existingHashes.size());
        return toSave.size();
    }

    // =========================================================================
    // 자동 DB 정리 — 매일 새벽 3시
    // =========================================================================

    /**
     * 전체 행 수가 MAX_ROWS(10,000)를 초과하면 초과분만큼 가장 오래된 레코드 삭제.
     * Railway MySQL 스토리지 한도 보호 목적.
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanOldTransactions() {
        long total = txRepository.count();
        if (total <= MAX_ROWS) return;

        long deleteCount = total - MAX_ROWS;
        int deleted = txRepository.deleteOldestN(deleteCount);
        log.info("[Indexer Cleanup] {}건 삭제 완료 (총 {}건 → {}건)",
                deleted, total, total - deleted);
    }

    // =========================================================================
    // private helpers
    // =========================================================================

    private OmtTransaction parseLog(Log logData, Map<String, String> watchTargets) {
        if (logData.getTopics() == null || logData.getTopics().size() < 3) {
            throw new IllegalArgumentException("topics 부족 (size="
                    + (logData.getTopics() == null ? "null" : logData.getTopics().size()) + ")");
        }

        String contractAddr = logData.getAddress().toLowerCase();
        String tokenSymbol  = watchTargets.getOrDefault(contractAddr, "UNKNOWN");
        String from = "0x" + logData.getTopics().get(1).substring(26);
        String to   = "0x" + logData.getTopics().get(2).substring(26);

        String rawData = logData.getData();
        BigInteger value = (rawData != null && rawData.length() > 2)
                ? new BigInteger(rawData.substring(2), 16)
                : BigInteger.ZERO;

        return OmtTransaction.builder()
                .txHash(logData.getTransactionHash())
                .blockNumber(logData.getBlockNumber().longValue())
                .fromAddress(from)
                .toAddress(to)
                .value(value)
                .tokenSymbol(tokenSymbol)
                .status("SUCCESS")
                .createdAt(LocalDateTime.now())
                .build();
    }
}
