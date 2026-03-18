package com.tem.cchain.controller;

import com.tem.cchain.entity.IndexerState;
import com.tem.cchain.entity.OmtTransaction;
import com.tem.cchain.repository.IndexerStateRepository;
import com.tem.cchain.repository.OmtTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
     * 1. 최근 OMT 거래 내역 조회 (최근 10건)
     */
    @GetMapping("/recent")
    public ResponseEntity<List<OmtTransaction>> getRecentTransactions() {
        // 최신 블록 순서대로 10개만 가져오기
        List<OmtTransaction> transactions = txRepository.findAll(
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "blockNumber"))
        ).getContent();
        
        return ResponseEntity.ok(transactions);
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
     * 3. 특정 주소의 거래 내역 검색
     */
    @GetMapping("/address/{address}")
    public ResponseEntity<List<OmtTransaction>> getTransactionsByAddress(@PathVariable String address) {
        // From 또는 To에 해당 주소가 포함된 거래 검색
        List<OmtTransaction> transactions = txRepository.findTop20ByFromAddressOrToAddressOrderByBlockNumberDesc(address, address);
        return ResponseEntity.ok(transactions);
    }
}