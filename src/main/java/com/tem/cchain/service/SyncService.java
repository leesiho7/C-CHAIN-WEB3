package com.tem.cchain.service;

import com.tem.cchain.entity.IndexerState;
import com.tem.cchain.repository.IndexerStateRepository;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.Log;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 블록체인 이벤트 인덱서.
 *
 * ── 변경 이력 ──────────────────────────────────────────────────
 * 이전: ethLogFlowable (RxJava 실시간 구독) → 메모리 누수 원인
 * 현재: @Scheduled 30초 폴링 → 실행 구간에만 메모리 사용, GC 즉시 수거
 * ──────────────────────────────────────────────────────────────
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncService {

    private final ObjectProvider<Web3j> web3jProvider;
    private final IndexerStateRepository stateRepository;
    private final IndexerBatchSaver batchSaver;

    @Value("${token.contract.address:none}")
    private String omtContractAddress;

    private static final String SERVICE_NAME  = "OMT_MULTI_INDEXER";
    private static final int BATCH_BLOCK_SIZE = 2000;
    private static final String TRANSFER_TOPIC =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

    private final Map<String, String> WATCH_TARGETS = new HashMap<>();

    @Getter
    private final AtomicBoolean running = new AtomicBoolean(false);

    @PostConstruct
    public void init() {
        if (!"none".equals(omtContractAddress)) {
            WATCH_TARGETS.put(omtContractAddress.toLowerCase(), "OMT");
        }
        WATCH_TARGETS.put("0x779877a7b0d9e8603169ddbd7836e478b4624789".toLowerCase(), "LINK");
        WATCH_TARGETS.put("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238".toLowerCase(), "USDC");
        WATCH_TARGETS.put("0xfff9976782d46cc05635d1e5f6bd092480392204".toLowerCase(), "WETH");
        WATCH_TARGETS.put("0xe24655d049e35922f306869a19c62394c8657155".toLowerCase(), "DAI");

        Web3j web3j = web3jProvider.getIfAvailable();
        if (web3j == null) {
            log.warn("[Indexer] Web3j 미연결 — 인덱서 비활성화");
            return;
        }
        running.set(true);
        log.info("[Indexer] 멀티 자산 폴링 인덱서 시작: {}", WATCH_TARGETS.values());
    }

    // =========================================================================
    // @Scheduled 폴링 — 30초마다 새 블록 처리
    // =========================================================================

    /**
     * 30초마다 실행. 마지막으로 처리한 블록 이후의 새 블록을 최대 2000개씩 처리.
     * 실행 후 로컬 변수가 모두 GC 대상이 되어 메모리 누수 없음.
     */
    @Scheduled(fixedDelay = 30_000)
    public void pollNewBlocks() {
        if (!running.get()) return;

        Web3j web3j = web3jProvider.getIfAvailable();
        if (web3j == null) return;

        try {
            BigInteger latestBlock = web3j.ethBlockNumber().send().getBlockNumber();

            long defaultStart = latestBlock.subtract(BigInteger.valueOf(10_000L)).longValue();
            IndexerState state = stateRepository.findById(SERVICE_NAME)
                    .orElse(IndexerState.builder()
                            .serviceName(SERVICE_NAME)
                            .lastBlockNumber(defaultStart)
                            .network("SEPOLIA")
                            .build());

            BigInteger fromBlock = BigInteger.valueOf(state.getLastBlockNumber() + 1);
            if (fromBlock.compareTo(latestBlock) > 0) return; // 새 블록 없음

            BigInteger toBlock = fromBlock
                    .add(BigInteger.valueOf(BATCH_BLOCK_SIZE - 1))
                    .min(latestBlock);

            List<Log> logs = fetchLogs(web3j, fromBlock, toBlock);
            int saved = batchSaver.saveBatch(logs, WATCH_TARGETS);

            state.setLastBlockNumber(toBlock.longValue());
            stateRepository.save(state);

            if (saved > 0 || fromBlock.compareTo(toBlock) < 0) {
                log.info("[Indexer] 블록 {}~{} 처리 완료 (저장 {}건)", fromBlock, toBlock, saved);
            }

        } catch (Exception e) {
            log.error("[Indexer] 폴링 오류: {}", e.getMessage());
        }
    }

    // =========================================================================
    // 관리자 제어 (start / stop)
    // =========================================================================

    public void stop() {
        running.set(false);
        log.info("[Indexer] 인덱서 중지됨");
    }

    public void start() {
        if (web3jProvider.getIfAvailable() == null) {
            log.warn("[Indexer] Web3j 미연결 — 재시작 불가");
            return;
        }
        running.set(true);
        log.info("[Indexer] 인덱서 재시작됨");
    }

    // =========================================================================
    // private helpers
    // =========================================================================

    private List<Log> fetchLogs(Web3j web3j, BigInteger from, BigInteger to) throws Exception {
        EthFilter filter = new EthFilter(
                DefaultBlockParameter.valueOf(from),
                DefaultBlockParameter.valueOf(to),
                WATCH_TARGETS.keySet().stream().toList());
        filter.addSingleTopic(TRANSFER_TOPIC);
        return web3j.ethGetLogs(filter).send().getLogs().stream()
                .map(r -> (Log) r.get())
                .collect(Collectors.toList());
    }
}
