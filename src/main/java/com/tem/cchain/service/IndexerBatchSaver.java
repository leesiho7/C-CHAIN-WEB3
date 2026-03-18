package com.tem.cchain.service;

import com.tem.cchain.entity.OmtTransaction;
import com.tem.cchain.repository.OmtTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.protocol.core.methods.response.Log;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 별도 Spring 빈으로 분리해 @Transactional 프록시가 정상 적용되도록 함.
 * SyncService 내부에서 self-call하면 트랜잭션이 무시되기 때문에 이 컴포넌트를 사용.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndexerBatchSaver {

    private final OmtTransactionRepository txRepository;

    /**
     * 로그 목록을 받아 중복 제거 후 saveAll() 로 배치 INSERT.
     * hibernate.jdbc.batch_size=50 + rewriteBatchedStatements=true 와 함께 동작.
     *
     * @param logs         web3j 로그 목록
     * @param watchTargets 컨트랙트주소 → 토큰심볼 매핑
     * @return 실제 저장된 건수
     */
    @Transactional
    public int saveBatch(List<Log> logs, Map<String, String> watchTargets) {
        if (logs.isEmpty()) return 0;

        // 1. 이번 배치의 해시 목록 추출
        List<String> hashes = logs.stream()
                .map(Log::getTransactionHash)
                .collect(Collectors.toList());

        // 2. 이미 저장된 해시를 한 번의 IN 쿼리로 일괄 조회
        Set<String> existingHashes = txRepository.findAllByTxHashIn(hashes)
                .stream()
                .map(OmtTransaction::getTxHash)
                .collect(Collectors.toSet());

        // 3. 신규 항목만 파싱
        List<OmtTransaction> toSave = new ArrayList<>();
        for (Log logData : logs) {
            if (existingHashes.contains(logData.getTransactionHash())) continue;
            try {
                toSave.add(parseLog(logData, watchTargets));
            } catch (Exception e) {
                log.warn("[Batch] 로그 파싱 실패 (txHash={}): {}", logData.getTransactionHash(), e.getMessage());
            }
        }

        if (toSave.isEmpty()) return 0;

        // 4. saveAll() → hibernate.jdbc.batch_size 설정으로 자동 bulk INSERT
        txRepository.saveAll(toSave);
        log.info("[Batch] {}건 저장 완료 (중복 {}건 제외)", toSave.size(), existingHashes.size());
        return toSave.size();
    }

    private OmtTransaction parseLog(Log logData, Map<String, String> watchTargets) {
        // topics 개수 검증: ERC-20 Transfer는 반드시 topics[0](서명), [1](from), [2](to) 3개 필요
        if (logData.getTopics() == null || logData.getTopics().size() < 3) {
            throw new IllegalArgumentException("topics 부족 (size=" +
                    (logData.getTopics() == null ? "null" : logData.getTopics().size()) + ")");
        }

        String contractAddr = logData.getAddress().toLowerCase();
        String tokenSymbol = watchTargets.getOrDefault(contractAddr, "UNKNOWN");

        // ERC-20 Transfer 이벤트: topics[1]=from, topics[2]=to (앞 26자리 패딩 제거)
        String from = "0x" + logData.getTopics().get(1).substring(26);
        String to   = "0x" + logData.getTopics().get(2).substring(26);

        // data 필드에서 value 추출 — null 또는 "0x"(빈 값)이면 0으로 처리
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
