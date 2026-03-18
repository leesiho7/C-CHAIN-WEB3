package com.tem.cchain.service;

import com.tem.cchain.entity.IndexerState;
import com.tem.cchain.repository.IndexerStateRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.Log;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SyncService {

    private final Web3j web3j;
    private final IndexerStateRepository stateRepository;
    private final IndexerBatchSaver batchSaver; // @Transactional 프록시가 적용된 별도 빈

    // ── 상수 ──────────────────────────────────────────────────────────────────

    private static final String SERVICE_NAME = "OMT_MULTI_INDEXER";

    /** 한 번의 eth_getLogs RPC 요청으로 처리할 최대 블록 범위 */
    private static final int BATCH_BLOCK_SIZE = 2000;

    /** ERC-20 Transfer(address indexed from, address indexed to, uint256 value) 이벤트 토픽 */
    private static final String TRANSFER_TOPIC =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

    // ── 감시 대상 컨트랙트 (주소 소문자 → 토큰 심볼) ────────────────────────

    /**
     * WATCH_TARGETS: Log Filtering 의 기준.
     * eth_getLogs 필터의 address 목록으로 사용되어 이 컨트랙트들의 이벤트만 수신.
     * 새 토큰 추가 시 여기에만 한 줄 추가하면 됨.
     */
    private static final Map<String, String> WATCH_TARGETS = Map.of(
            "0xYourOMTContractAddress".toLowerCase(),              "OMT",
            "0x779877A7B0D9E8603169DdbD7836e478b4624789".toLowerCase(), "LINK",
            "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238".toLowerCase(), "USDC",
            "0xfFf9976782d46CC05635D1E5f6BD092480392204".toLowerCase(), "WETH",
            "0xe24655d049e35922f306869a19c62394c8657155".toLowerCase(), "DAI"
    );

    // ── 초기화 ────────────────────────────────────────────────────────────────

    @PostConstruct
    public void init() {
        if (web3j == null) {
            log.warn("[Indexer] Web3j 빈이 null — RPC URL 미설정으로 인덱서 비활성화");
            return;
        }
        log.info("[Indexer] 멀티 자산 인덱서 시작: {}", WATCH_TARGETS.values());
        // 실시간 리스너를 먼저 등록해 sync 중 발생하는 이벤트도 놓치지 않음
        startRealTimeListener();
        // 과거 블록 동기화는 별도 스레드에서 실행 — 앱 시작 스레드를 블로킹하지 않음
        CompletableFuture.runAsync(this::syncOldBlocks);
    }

    // ── 과거 블록 동기화 (Back-filling) ──────────────────────────────────────

    /**
     * syncOldBlocks: 마지막 처리 블록부터 현재 블록까지 BATCH_BLOCK_SIZE 단위로
     * 분할하여 RPC 호출. 각 청크마다 batchSaver.saveBatch() 로 bulk INSERT.
     */
    public void syncOldBlocks() {
        try {
            IndexerState state = stateRepository.findById(SERVICE_NAME)
                    .orElse(IndexerState.builder()
                            .serviceName(SERVICE_NAME)
                            .lastBlockNumber(5_000_000L) // 동기화 시작 블록
                            .network("SEPOLIA")
                            .build());

            BigInteger fromBlock = BigInteger.valueOf(state.getLastBlockNumber() + 1);
            BigInteger latestBlock = web3j.ethBlockNumber().send().getBlockNumber();

            if (fromBlock.compareTo(latestBlock) > 0) {
                log.info("[Indexer] 이미 최신 블록 동기화 완료 (lastBlock={})", state.getLastBlockNumber());
                return;
            }

            log.info("[Indexer] 과거 블록 동기화 시작: {} → {} (총 {} 블록)",
                    fromBlock, latestBlock, latestBlock.subtract(fromBlock).add(BigInteger.ONE));

            int totalSaved = 0;
            BigInteger current = fromBlock;

            // BATCH_BLOCK_SIZE 단위로 블록 범위를 분할하여 순차 처리
            while (current.compareTo(latestBlock) <= 0) {
                BigInteger end = current
                        .add(BigInteger.valueOf(BATCH_BLOCK_SIZE - 1))
                        .min(latestBlock);

                List<Log> logs = fetchLogs(current, end);
                log.debug("[Batch] 블록 {}~{}: {}건 로그 발견", current, end, logs.size());

                // IndexerBatchSaver 를 통해 @Transactional + saveAll() 실행
                totalSaved += batchSaver.saveBatch(logs, WATCH_TARGETS);

                // 청크 완료 시마다 진행 상태 저장 (장애 복구 체크포인트)
                state.setLastBlockNumber(end.longValue());
                stateRepository.save(state);

                current = end.add(BigInteger.ONE);
            }

            log.info("[Indexer] 과거 블록 동기화 완료. 총 {}건 저장, 마지막 블록: {}",
                    totalSaved, latestBlock);

        } catch (Exception e) {
            log.error("[Indexer] syncOldBlocks 오류: ", e);
        }
    }

    // ── 실시간 리스너 ─────────────────────────────────────────────────────────

    /**
     * startRealTimeListener: LATEST 블록 이벤트를 구독.
     * 실시간 로그도 batchSaver.saveBatch() 로 통일하여 중복 방지 로직 공유.
     */
    public void startRealTimeListener() {
        EthFilter filter = buildFilter(
                DefaultBlockParameterName.LATEST,
                DefaultBlockParameterName.LATEST
        );

        web3j.ethLogFlowable(filter).subscribe(
                logData -> {
                    batchSaver.saveBatch(List.of(logData), WATCH_TARGETS);
                    updateState(logData.getBlockNumber());
                },
                throwable -> log.error("[Indexer] 실시간 리스너 오류: ", throwable)
        );

        log.info("[Indexer] 실시간 리스너 등록 완료");
    }

    // ── 내부 유틸 ─────────────────────────────────────────────────────────────

    private List<Log> fetchLogs(BigInteger from, BigInteger to) throws Exception {
        EthFilter filter = buildFilter(
                DefaultBlockParameter.valueOf(from),
                DefaultBlockParameter.valueOf(to)
        );
        return web3j.ethGetLogs(filter).send().getLogs()
                .stream()
                .map(r -> (Log) r)
                .collect(Collectors.toList());
    }

    private EthFilter buildFilter(
            org.web3j.protocol.core.DefaultBlockParameter from,
            org.web3j.protocol.core.DefaultBlockParameter to) {

        EthFilter filter = new EthFilter(
                from,
                to,
                WATCH_TARGETS.keySet().stream().toList() // Log Filtering: WATCH_TARGETS 주소만 수신
        );
        filter.addSingleTopic(TRANSFER_TOPIC); // ERC-20 Transfer 이벤트만 필터링
        return filter;
    }

    private void updateState(BigInteger blockNumber) {
        stateRepository.findById(SERVICE_NAME).ifPresent(state -> {
            if (blockNumber.longValue() > state.getLastBlockNumber()) {
                state.setLastBlockNumber(blockNumber.longValue());
                stateRepository.save(state);
            }
        });
    }
}
