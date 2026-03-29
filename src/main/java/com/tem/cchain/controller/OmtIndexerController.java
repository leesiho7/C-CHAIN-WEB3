package com.tem.cchain.controller;

import com.tem.cchain.dto.KeysetPage;
import com.tem.cchain.entity.IndexerState;
import com.tem.cchain.entity.OmtTransaction;
import com.tem.cchain.repository.IndexerStateRepository;
import com.tem.cchain.repository.OmtTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/indexer")
@RequiredArgsConstructor
public class OmtIndexerController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final OmtTransactionRepository txRepository;
    private final IndexerStateRepository stateRepository;

    /**
     * 1. 최근 거래 내역 조회 — Keyset 페이지네이션
     * ?limit=20           → 최신 N건
     * ?limit=20&before=42 → id < 42 인 최신 N건 (다음 페이지)
     */
    @GetMapping("/recent")
    public ResponseEntity<KeysetPage<OmtTransaction>> getRecentTransactions(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) Long before) {

        int safeLimit = Math.min(limit, MAX_LIMIT);
        // +1 로 다음 페이지 존재 여부 확인
        var pageable = PageRequest.of(0, safeLimit + 1);

        List<OmtTransaction> rows = (before != null)
                ? txRepository.findByIdLessThanOrderByIdDesc(before, pageable)
                : txRepository.findByOrderByIdDesc(pageable);

        boolean hasMore = rows.size() > safeLimit;
        if (hasMore) rows = rows.subList(0, safeLimit);
        Long nextCursor = hasMore ? rows.get(rows.size() - 1).getId() : null;

        return ResponseEntity.ok(new KeysetPage<>(rows, hasMore, nextCursor));
    }

    /**
     * 2. 인덱서 상태 및 요약 통계 조회
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getIndexerStatus() {
        Map<String, Object> status = new HashMap<>();
        
        // 현재 인덱서가 어디까지 읽었는지
        IndexerState state = stateRepository.findById("OMT_MULTI_INDEXER").orElse(null);
        
        // 전체 OMT 거래 횟수
        long totalCount = txRepository.count();

        status.put("lastBlock", state != null ? state.getLastBlockNumber() : 0);
        status.put("totalTransactions", totalCount);
        status.put("network", state != null ? state.getNetwork() : "UNKNOWN");
        status.put("isSyncing", true); // 현재 리스너가 돌고 있으므로 true

        return ResponseEntity.ok(status);
    }

    /**
     * 3. 특정 주소의 거래 내역 검색 — Keyset 페이지네이션
     * ?limit=20           → 최신 N건
     * ?limit=20&before=42 → id < 42 인 최신 N건 (다음 페이지)
     */
    @GetMapping("/address/{address}")
    public ResponseEntity<KeysetPage<OmtTransaction>> getTransactionsByAddress(
            @PathVariable String address,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) Long before) {

        int safeLimit = Math.min(limit, MAX_LIMIT);
        var pageable = PageRequest.of(0, safeLimit + 1);

        List<OmtTransaction> rows = (before != null)
                ? txRepository.findByAddressBefore(address, before, pageable)
                : txRepository.findLatestByAddress(address, pageable);

        boolean hasMore = rows.size() > safeLimit;
        if (hasMore) rows = rows.subList(0, safeLimit);
        Long nextCursor = hasMore ? rows.get(rows.size() - 1).getId() : null;

        return ResponseEntity.ok(new KeysetPage<>(rows, hasMore, nextCursor));
    }
}