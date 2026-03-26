package com.tem.cchain.controller;

import com.tem.cchain.entity.IndexerState;
import com.tem.cchain.entity.OmtTransaction;
import com.tem.cchain.repository.IndexerStateRepository;
import com.tem.cchain.repository.OmtTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/indexer")
@RequiredArgsConstructor
public class OmtIndexerController {

    private final OmtTransactionRepository txRepository;
    private final IndexerStateRepository stateRepository;

    /**
     * 1. 최근 거래 내역 조회 — 최대 100건 (blockNumber DESC)
     * DB LIMIT으로 처리해 in-memory 슬라이딩 없이 메모리 안전.
     */
    @GetMapping("/recent")
    public ResponseEntity<List<OmtTransaction>> getRecentTransactions() {
        return ResponseEntity.ok(txRepository.findTop100ByOrderByBlockNumberDesc());
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
     * 3. 특정 주소의 거래 내역 검색 — 최대 50건
     */
    @GetMapping("/address/{address}")
    public ResponseEntity<List<OmtTransaction>> getTransactionsByAddress(@PathVariable String address) {
        return ResponseEntity.ok(
            txRepository.findTop50ByFromAddressOrToAddressOrderByBlockNumberDesc(address, address));
    }
}