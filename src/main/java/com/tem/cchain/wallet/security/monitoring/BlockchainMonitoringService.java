package com.tem.cchain.wallet.security.monitoring;

import com.tem.cchain.entity.OmtTransaction;
import com.tem.cchain.repository.OmtTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.Log;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ════════════════════════════════════════════════════════════
 * [블록체인 네트워크 모니터링 서비스] BlockchainMonitoringService
 * ════════════════════════════════════════════════════════════
 *
 * 온체인 인덱서에 부착된 실시간 감시 서비스입니다.
 * SyncService 가 블록을 인덱싱하는 것과 독립적으로,
 * 이 서비스는 블록체인 전체 흐름을 감시하고
 * DB와의 정합성을 주기적으로 검증합니다.
 *
 * ── 감시 항목 ────────────────────────────────────────────────
 *
 * [MONITOR-01] 입금 흐름 이상 징후 탐지 (5분마다)
 *   - 최근 100블록 내 Transfer 이벤트를 온체인에서 직접 조회
 *   - DB에 누락된 트랜잭션이 있으면 경고 로그 발생
 *   - 누락률 10% 초과 시 Critical 알림
 *
 * [MONITOR-02] DB 정합성 실시간 검증 (10분마다)
 *   - DB에 저장된 트랜잭션 해시를 온체인에서 재검증
 *   - 온체인에 존재하지 않는 DB 레코드(유령 트랜잭션) 탐지
 *   - 유령 트랜잭션은 잔액 조작 시도의 징후일 수 있음
 *
 * [MONITOR-03] 블록 갭 탐지 (5분마다)
 *   - 인덱서가 처리한 마지막 블록과 현재 블록 간격이
 *     설정값(기본 500블록) 초과 시 동기화 지연 경고
 *
 * ── SyncService와의 차이 ─────────────────────────────────────
 * SyncService : 블록을 순서대로 읽어 DB에 저장 (인덱싱)
 * 이 서비스   : 인덱싱된 데이터의 무결성을 독립적으로 검증 (감시)
 *
 * ── 중요 ────────────────────────────────────────────────────
 * 이 서비스는 DB를 수정하지 않습니다. 이상 탐지 시
 * 경고 로그만 출력하며, 수정은 운영자가 직접 수행합니다.
 * ════════════════════════════════════════════════════════════
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlockchainMonitoringService {

    private final ObjectProvider<Web3j> web3jProvider;
    private final OmtTransactionRepository txRepository;

    // ERC-20 Transfer 이벤트 토픽 (keccak256("Transfer(address,address,uint256)"))
    private static final String TRANSFER_TOPIC =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

    /** 정합성 검증 시 조회할 최근 블록 범위 */
    @Value("${wallet.monitoring.check-block-range:100}")
    private int checkBlockRange;

    /** 블록 갭 경고 임계값 (이 값 이상 지연되면 경고) */
    @Value("${wallet.monitoring.max-block-gap:500}")
    private int maxBlockGap;

    /** 감시 대상 토큰 컨트랙트 주소 목록 (콤마 구분, 미설정 시 내장 기본값 사용) */
    @Value("${wallet.monitoring.watch-addresses:}")
    private String watchAddressesConfig;

    // SyncService 와 동일한 기본 감시 주소 (환경변수 미설정 시 fallback)
    private static final List<String> DEFAULT_WATCH_ADDRESSES = List.of(
        "0x779877a7b0d9e8603169ddbd7836e478b4624789", // LINK
        "0x1c7d4b196cb0c7b01d743fbc6116a902379c7238", // USDC
        "0xfff9976782d46cc05635d1e5f6bd092480392204", // WETH
        "0xe24655d049e35922f306869a19c62394c8657155"  // DAI
    );

    // =========================================================
    // MONITOR-01: 입금 흐름 이상 징후 탐지 (5분마다)
    // =========================================================

    /**
     * 최근 N블록의 온체인 Transfer 이벤트를 직접 조회하여
     * DB에 누락된 입금 트랜잭션이 있는지 검증합니다.
     *
     * 누락된 트랜잭션은 인덱서 장애 또는 데이터 손실의 신호일 수 있습니다.
     * 누락률이 10%를 초과하면 Critical 레벨로 경고합니다.
     */
    @Scheduled(fixedDelay = 15 * 60 * 1000) // 15분마다
    public void monitorDepositFlowAnomalies() {
        Web3j web3j = web3jProvider.getIfAvailable();
        if (web3j == null) {
            log.warn("[Monitor-01] Web3j 미연결 — 입금 흐름 감시 건너뜀");
            return;
        }

        try {
            BigInteger latestBlock = web3j.ethBlockNumber().send().getBlockNumber();
            BigInteger fromBlock = latestBlock.subtract(BigInteger.valueOf(checkBlockRange));

            // ① 온체인에서 최근 N블록의 Transfer 이벤트 직접 조회
            List<Log> onChainLogs = fetchTransferLogs(web3j, fromBlock, latestBlock);

            if (onChainLogs.isEmpty()) {
                log.debug("[Monitor-01] 최근 {}블록 Transfer 이벤트 없음 (정상)", checkBlockRange);
                return;
            }

            // ② 온체인 트랜잭션 해시 집합 구성
            Set<String> onChainHashes = onChainLogs.stream()
                    .map(Log::getTransactionHash)
                    .collect(Collectors.toSet());

            // ③ DB에 저장된 트랜잭션 해시 집합 조회
            List<OmtTransaction> dbTxs = txRepository.findAllByTxHashIn(
                    List.copyOf(onChainHashes));
            Set<String> dbHashes = dbTxs.stream()
                    .map(OmtTransaction::getTxHash)
                    .collect(Collectors.toSet());

            // ④ DB에 없는 온체인 트랜잭션 = 누락 트랜잭션 탐지
            Set<String> missingHashes = onChainHashes.stream()
                    .filter(hash -> !dbHashes.contains(hash))
                    .collect(Collectors.toSet());

            if (!missingHashes.isEmpty()) {
                double missingRate = (double) missingHashes.size() / onChainHashes.size() * 100;

                if (missingRate >= 10.0) {
                    // 누락률 10% 이상 → Critical 경고
                    log.error("""
                            [Monitor-01] ❌ CRITICAL: 입금 누락률 {}% 탐지!
                            온체인 {}건 중 {}건 DB 미반영.
                            누락 txHash 샘플: {}
                            블록 범위: {} ~ {}
                            """,
                            String.format("%.1f", missingRate),
                            onChainHashes.size(),
                            missingHashes.size(),
                            missingHashes.stream().limit(3).collect(Collectors.joining(", ")),
                            fromBlock, latestBlock);
                } else {
                    // 누락률 10% 미만 → 일반 경고 (인덱서 지연으로 곧 반영될 수 있음)
                    log.warn("[Monitor-01] ⚠ 입금 미반영 {}건 탐지 (누락률 {}%, 인덱서 지연 가능)",
                            missingHashes.size(), String.format("%.1f", missingRate));
                }
            } else {
                log.info("[Monitor-01] ✓ 입금 흐름 정상: 온체인 {}건 모두 DB 반영 확인",
                        onChainHashes.size());
            }

        } catch (Exception e) {
            log.error("[Monitor-01] 입금 흐름 감시 중 오류: {}", e.getMessage());
        }
    }

    // =========================================================
    // MONITOR-02: DB 정합성 실시간 검증 (10분마다)
    // =========================================================

    /**
     * DB에 저장된 최근 트랜잭션들이 실제로 온체인에 존재하는지 검증합니다.
     *
     * "유령 트랜잭션": DB에는 있으나 온체인에 없는 레코드.
     * 이는 다음 상황의 징후일 수 있습니다:
     *   - 잔액 직접 조작(DB 해킹)
     *   - 트랜잭션 롤백 미처리
     *   - 인덱서 버그
     *
     * 유령 트랜잭션 발견 시 Critical 레벨로 경고합니다.
     */
    @Scheduled(fixedDelay = 30 * 60 * 1000) // 30분마다
    public void verifyDbConsistency() {
        Web3j web3j = web3jProvider.getIfAvailable();
        if (web3j == null) {
            log.warn("[Monitor-02] Web3j 미연결 — DB 정합성 검증 건너뜀");
            return;
        }

        try {
            // ① DB에서 최근 10건 조회 (최신 순) — 샘플링으로도 유령 트랜잭션 탐지에 충분
            List<OmtTransaction> recentDbTxs = txRepository.findAll(
                    org.springframework.data.domain.PageRequest.of(
                            0, 10,
                            org.springframework.data.domain.Sort.by(
                                    org.springframework.data.domain.Sort.Direction.DESC,
                                    "blockNumber")
                    )
            ).getContent();

            if (recentDbTxs.isEmpty()) {
                log.debug("[Monitor-02] DB 트랜잭션 없음, 검증 건너뜀");
                return;
            }

            int ghostCount = 0;

            for (OmtTransaction dbTx : recentDbTxs) {
                // ② 각 트랜잭션을 온체인에서 직접 조회
                var onChainTx = web3j
                        .ethGetTransactionByHash(dbTx.getTxHash())
                        .send()
                        .getTransaction();

                if (onChainTx.isEmpty()) {
                    // 유령 트랜잭션 발견!
                    ghostCount++;
                    log.error("""
                            [Monitor-02] ❌ 유령 트랜잭션 발견!
                            txHash  : {}
                            from    : {}
                            to      : {}
                            value   : {}
                            블록번호 : {}
                            저장시각 : {}
                            → DB에는 존재하나 온체인에서 확인 불가. 즉시 운영자 확인 필요!
                            """,
                            dbTx.getTxHash(),
                            dbTx.getFromAddress(),
                            dbTx.getToAddress(),
                            dbTx.getValue(),
                            dbTx.getBlockNumber(),
                            dbTx.getCreatedAt());
                }
            }

            if (ghostCount == 0) {
                log.info("[Monitor-02] ✓ DB 정합성 검증 통과: {}건 모두 온체인 확인 완료",
                        recentDbTxs.size());
            } else {
                log.error("[Monitor-02] ❌ CRITICAL: 유령 트랜잭션 {}건 탐지! 즉시 대응 필요.",
                        ghostCount);
            }

        } catch (Exception e) {
            log.error("[Monitor-02] DB 정합성 검증 중 오류: {}", e.getMessage());
        }
    }

    // =========================================================
    // MONITOR-03: 블록 갭(인덱서 지연) 탐지 (5분마다)
    // =========================================================

    /**
     * 인덱서가 처리한 마지막 블록과 현재 온체인 최신 블록의 차이를 감시합니다.
     * maxBlockGap 초과 시 인덱서 지연으로 간주하고 경고합니다.
     *
     * 블록 갭이 크면 실시간 입금 확인이 지연되며,
     * 사용자 경험과 거래소 운영에 직접적 영향을 줍니다.
     */
    @Scheduled(fixedDelay = 15 * 60 * 1000) // 15분마다
    public void detectBlockGap() {
        Web3j web3j = web3jProvider.getIfAvailable();
        if (web3j == null) return;

        try {
            // ① 현재 온체인 최신 블록
            BigInteger latestOnChain = web3j.ethBlockNumber().send().getBlockNumber();

            // ② DB에 저장된 마지막 블록 번호 (OmtTransaction 기준)
            // findAll + PageRequest(0, 1, DESC blockNumber) 로 최신 블록 조회
            List<OmtTransaction> latestInDb = txRepository.findAll(
                    org.springframework.data.domain.PageRequest.of(
                            0, 1,
                            org.springframework.data.domain.Sort.by(
                                    org.springframework.data.domain.Sort.Direction.DESC,
                                    "blockNumber")
                    )
            ).getContent();

            if (latestInDb.isEmpty()) {
                log.debug("[Monitor-03] DB 트랜잭션 없음, 블록 갭 감시 건너뜀");
                return;
            }

            long dbLastBlock = latestInDb.get(0).getBlockNumber();
            long gap = latestOnChain.longValue() - dbLastBlock;

            if (gap > maxBlockGap) {
                log.warn("""
                        [Monitor-03] ⚠ 인덱서 지연 감지!
                        온체인 최신 블록 : {}
                        DB 마지막 블록  : {}
                        블록 갭         : {} 블록
                        (임계값: {}블록 초과)
                        → SyncService 상태를 확인하세요.
                        """, latestOnChain, dbLastBlock, gap, maxBlockGap);
            } else {
                log.info("[Monitor-03] ✓ 인덱서 동기화 정상: 갭 {}블록 (임계 {}블록)",
                        gap, maxBlockGap);
            }

        } catch (Exception e) {
            log.error("[Monitor-03] 블록 갭 감시 중 오류: {}", e.getMessage());
        }
    }

    // =========================================================
    // private helpers
    // =========================================================

    /**
     * 지정된 블록 범위에서 ERC-20 Transfer 이벤트 로그를 조회합니다.
     * SyncService.fetchLogs()와 동일한 방식이나, 감시 목적으로 독립 호출합니다.
     */
    private List<Log> fetchTransferLogs(Web3j web3j,
                                         BigInteger fromBlock,
                                         BigInteger toBlock) throws Exception {
        List<String> addresses = resolveWatchAddresses();
        EthFilter filter = new EthFilter(
                DefaultBlockParameter.valueOf(fromBlock),
                DefaultBlockParameter.valueOf(toBlock),
                addresses
        );
        filter.addSingleTopic(TRANSFER_TOPIC);

        return web3j.ethGetLogs(filter).send().getLogs().stream()
                .map(r -> (Log) r.get())
                .collect(Collectors.toList());
    }

    /**
     * 감시 주소 목록을 반환한다.
     * wallet.monitoring.watch-addresses 가 설정되어 있으면 그 값을 사용하고,
     * 비어 있으면 DEFAULT_WATCH_ADDRESSES(SyncService 와 동일한 목록)를 사용한다.
     */
    private List<String> resolveWatchAddresses() {
        if (watchAddressesConfig != null && !watchAddressesConfig.isBlank()) {
            return Arrays.stream(watchAddressesConfig.split(","))
                    .map(String::trim)
                    .map(String::toLowerCase)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        }
        return DEFAULT_WATCH_ADDRESSES;
    }
}
