package com.tem.cchain.service;

import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.Log;

import com.tem.cchain.entity.IndexerState;
import com.tem.cchain.repository.IndexerStateRepository;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class SyncService {

    // Web3j 빈이 null일 수 있으므로 ObjectProvider로 안전하게 주입
    // (RPC URL 미설정 시 Web3Config에서 null 반환 → 직접 주입 시 앱 시작 실패)
    private final ObjectProvider<Web3j> web3jProvider;
    private final IndexerStateRepository stateRepository;
    private final IndexerBatchSaver batchSaver;

    @Value("${token.contract.address:none}")
    private String omtContractAddress;

    private static final String SERVICE_NAME   = "OMT_MULTI_INDEXER";
    private static final int BATCH_BLOCK_SIZE  = 2000;
    /** RPC Rate Limit 방지: 배치 요청 사이 대기 시간 (ms) */
    private static final long BATCH_DELAY_MS   = 300L;
    private static final String TRANSFER_TOPIC =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

    private final java.util.Map<String, String> WATCH_TARGETS = new java.util.HashMap<>();

    @PostConstruct
    public void init() {
        // 인덱싱 대상 설정 (OMT는 설정값에서 가져옴)
        if (!"none".equals(omtContractAddress)) {
            WATCH_TARGETS.put(omtContractAddress.toLowerCase(), "OMT");
        }
        // 기타 고정 자산
        WATCH_TARGETS.put("0x779877a7b0d9e8603169ddbd7836e478b4624789".toLowerCase(), "LINK");
        WATCH_TARGETS.put("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238".toLowerCase(), "USDC");
        WATCH_TARGETS.put("0xfff9976782d46cc05635d1e5f6bd092480392204".toLowerCase(), "WETH");
        WATCH_TARGETS.put("0xe24655d049e35922f306869a19c62394c8657155".toLowerCase(), "DAI");

        Web3j web3j = web3jProvider.getIfAvailable();
        if (web3j == null) {
            log.warn("[Indexer] Web3j 빈 없음 — RPC URL 미설정으로 인덱서 비활성화");
            return;
        }

        log.info("[Indexer] 멀티 자산 인덱싱 시작: {}", WATCH_TARGETS.values());

        // 실시간 리스너 즉시 가동
        startRealTimeListener(web3j);

        // 과거 동기화는 전용 단일 스레드에서 실행
        // (기본 ForkJoinPool 사용 시 RPC 블로킹 I/O가 공용 스레드 풀을 점유하는 문제 방지)
        Executors.newSingleThreadExecutor().submit(this::syncOldBlocks);
    }

    /**
     * 과거 블록 동기화 (Back-filling)
     * 2000블록 단위로 나눠 RPC 호출 + 배치 사이 300ms 딜레이로 Rate Limit 방지
     */
    public void syncOldBlocks() {
        Web3j web3j = web3jProvider.getIfAvailable();
        if (web3j == null) return;

        try {
            BigInteger latestBlock = web3j.ethBlockNumber().send().getBlockNumber();

            long defaultStartBlock = latestBlock.subtract(BigInteger.valueOf(10_000L)).longValue();
            IndexerState state = stateRepository.findById(SERVICE_NAME)
                    .orElse(IndexerState.builder()
                            .serviceName(SERVICE_NAME)
                            .lastBlockNumber(defaultStartBlock)
                            .network("SEPOLIA")
                            .build());

            BigInteger fromBlock = BigInteger.valueOf(state.getLastBlockNumber() + 1);

            if (fromBlock.compareTo(latestBlock) > 0) {
                log.info("[Indexer] 동기화할 새로운 블록 없음");
                return;
            }

            log.info("[Indexer] 동기화 범위: {} → {}", fromBlock, latestBlock);

            BigInteger current = fromBlock;
            while (current.compareTo(latestBlock) <= 0) {
                BigInteger end = current.add(BigInteger.valueOf(BATCH_BLOCK_SIZE - 1)).min(latestBlock);

                List<Log> logs = fetchLogs(web3j, current, end);
                batchSaver.saveBatch(logs, WATCH_TARGETS);

                state.setLastBlockNumber(end.longValue());
                stateRepository.save(state);

                current = end.add(BigInteger.ONE);
                log.info("[Indexer] 블록 {} 까지 동기화 완료", end);

                // RPC Rate Limit 방지: 배치 요청 사이 대기
                Thread.sleep(BATCH_DELAY_MS);
            }

            log.info("[Indexer] 과거 블록 동기화 완료");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[Indexer] 동기화 인터럽트");
        } catch (Exception e) {
            log.error("[Indexer] 동기화 오류: ", e);
        }
    }

    /**
     * 실시간 이벤트 감시 (Real-time Listener)
     */
    public void startRealTimeListener(Web3j web3j) {
        EthFilter filter = buildFilter(
                web3j, DefaultBlockParameterName.LATEST, DefaultBlockParameterName.LATEST);

        web3j.ethLogFlowable(filter).subscribe(
                logData -> {
                    batchSaver.saveBatch(List.of(logData), WATCH_TARGETS);
                    updateState(logData.getBlockNumber());
                },
                throwable -> log.error("[Indexer] 리스너 에러: ", throwable)
        );

        log.info("[Indexer] 실시간 리스너 등록 완료");
    }

    private List<Log> fetchLogs(Web3j web3j, BigInteger from, BigInteger to) throws Exception {
        EthFilter filter = buildFilter(
                web3j, DefaultBlockParameter.valueOf(from), DefaultBlockParameter.valueOf(to));
        return web3j.ethGetLogs(filter).send().getLogs().stream()
                .map(r -> (Log) r.get())
                .collect(Collectors.toList());
    }

    private EthFilter buildFilter(Web3j web3j,
                                  org.web3j.protocol.core.DefaultBlockParameter from,
                                  org.web3j.protocol.core.DefaultBlockParameter to) {
        EthFilter filter = new EthFilter(from, to, WATCH_TARGETS.keySet().stream().toList());
        filter.addSingleTopic(TRANSFER_TOPIC);
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
