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
    private final IndexerBatchSaver batchSaver; // 트랜잭션 처리를 위한 별도 빈

    // ── 설정값 ──
    private static final String SERVICE_NAME = "OMT_MULTI_INDEXER";
    private static final int BATCH_BLOCK_SIZE = 2000; // RPC 부하 방지를 위한 블록 청크 단위
    private static final String TRANSFER_TOPIC = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

    // ── 감시 대상 (본인의 OMT 주소로 교체하세요) ──
    private static final Map<String, String> WATCH_TARGETS = Map.of(
            "0xYourOMTContractAddress".toLowerCase(), "OMT",
            "0x779877a7b0d9e8603169ddbd7836e478b4624789".toLowerCase(), "LINK",
            "0x1c7d4b196cb0c7b01d743fbc6116a902379c7238".toLowerCase(), "USDC",
            "0xfff9976782d46cc05635d1e5f6bd092480392204".toLowerCase(), "WETH",
            "0xe24655d049e35922f306869a19c62394c8657155".toLowerCase(), "DAI"
    );

    @PostConstruct
    public void init() {
        if (web3j == null) {
            log.warn("[Indexer] RPC 연결 실패. 인덱서를 시작할 수 없습니다.");
            return;
        }

        log.info("[Indexer] 멀티 자산 인덱싱 시작: {}", WATCH_TARGETS.values());

        // 1. 실시간 리스너 즉시 가동
        startRealTimeListener();

        // 2. 과거 데이터 동기화 (비동기 처리: 앱 로딩 방해 금지)
        CompletableFuture.runAsync(this::syncOldBlocks);
    }

    /**
     * 과거 블록 동기화 (Back-filling)
     */
    public void syncOldBlocks() {
        try {
            BigInteger latestBlock = web3j.ethBlockNumber().send().getBlockNumber();

            // DB에 진행 상태가 없으면 현재 블록 기준 최근 10,000블록부터 시작
            // 고정값(5_000_000L) 사용 시 수백만 블록 동기화로 초기 로딩이 수십 분 걸리는 문제 방지
            long defaultStartBlock = latestBlock.subtract(BigInteger.valueOf(10_000L)).longValue();
            IndexerState state = stateRepository.findById(SERVICE_NAME)
                    .orElse(IndexerState.builder()
                            .serviceName(SERVICE_NAME)
                            .lastBlockNumber(defaultStartBlock)
                            .network("SEPOLIA")
                            .build());

            BigInteger fromBlock = BigInteger.valueOf(state.getLastBlockNumber() + 1);

            if (fromBlock.compareTo(latestBlock) > 0) {
                log.info("[Indexer] 동기화할 새로운 블록이 없습니다.");
                return;
            }

            log.info("[Indexer] 동기화 범위: {} -> {}", fromBlock, latestBlock);

            BigInteger current = fromBlock;
            while (current.compareTo(latestBlock) <= 0) {
                BigInteger end = current.add(BigInteger.valueOf(BATCH_BLOCK_SIZE - 1)).min(latestBlock);

                // RPC 호출 (로그 가져오기)
                List<Log> logs = fetchLogs(current, end);
                
                // BatchSaver를 이용한 대량 저장 (중복 체크 포함)
                batchSaver.saveBatch(logs, WATCH_TARGETS);

                // 체크포인트 저장 (서버 다운 대비)
                state.setLastBlockNumber(end.longValue());
                stateRepository.save(state);

                current = end.add(BigInteger.ONE);
                log.info("[Indexer] {} 블록까지 동기화 완료...", end);
            }
            log.info("[Indexer] 모든 과거 데이터 동기화 완료.");

        } catch (Exception e) {
            log.error("[Indexer] 동기화 중 오류 발생: ", e);
        }
    }

    /**
     * 실시간 이벤트 감시 (Real-time Listener)
     */
    public void startRealTimeListener() {
        EthFilter filter = buildFilter(DefaultBlockParameterName.LATEST, DefaultBlockParameterName.LATEST);

        web3j.ethLogFlowable(filter).subscribe(
                logData -> {
                    batchSaver.saveBatch(List.of(logData), WATCH_TARGETS);
                    updateState(logData.getBlockNumber());
                },
                throwable -> log.error("[Indexer] 리스너 에러: ", throwable)
        );
    }

    // ── 유틸리티 메서드 ──

    private List<Log> fetchLogs(BigInteger from, BigInteger to) throws Exception {
        EthFilter filter = buildFilter(DefaultBlockParameter.valueOf(from), DefaultBlockParameter.valueOf(to));
        return web3j.ethGetLogs(filter).send().getLogs().stream()
                .map(r -> (Log) r.get())
                .collect(Collectors.toList());
    }

    private EthFilter buildFilter(org.web3j.protocol.core.DefaultBlockParameter from, 
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