package com.tem.cchain.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.utils.Convert;

import java.math.BigDecimal;
import java.math.BigInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    // Web3j가 null일 수 있으므로 ObjectProvider로 안전하게 주입
    private final ObjectProvider<Web3j> web3jProvider;

    // OMT 컨트랙트 주소는 application.properties에서 가져옴 (TokenService와 공유)
    @Value("${token.contract.address:none}")
    private String omtContractAddress;

    /**
     * 1. 실시간 ETH 잔액 조회 (Wei → ETH 변환)
     * @param walletAddress 조회할 지갑 주소 (세션에서 전달)
     */
    public double getEthBalance(String walletAddress) {
        Web3j web3j = web3jProvider.getIfAvailable();
        if (web3j == null || walletAddress == null || walletAddress.isBlank()) return 0.0;

        try {
            BigInteger wei = web3j.ethGetBalance(walletAddress, DefaultBlockParameterName.LATEST)
                    .send().getBalance();
            BigDecimal eth = Convert.fromWei(wei.toString(), Convert.Unit.ETHER);
            log.info("[Wallet] ETH 잔액: {} ETH ({})", eth, walletAddress);
            return eth.doubleValue();
        } catch (Exception e) {
            log.error("[Wallet] ETH 잔액 조회 실패 ({}): {}", walletAddress, e.getMessage());
            return 0.0;
        }
    }

    /**
     * 2. 실시간 OMT 토큰 잔액 조회 (ERC-20 balanceOf eth_call)
     * @param walletAddress 조회할 지갑 주소 (세션에서 전달)
     */
    public double getOmtBalance(String walletAddress) {
        Web3j web3j = web3jProvider.getIfAvailable();
        if (web3j == null || walletAddress == null || walletAddress.isBlank()) return 0.0;
        if ("none".equals(omtContractAddress)) return 0.0;

        try {
            // balanceOf(address) 함수 셀렉터(0x70a08231) + 주소 패딩
            String functionData = "0x70a08231000000000000000000000000"
                    + walletAddress.replace("0x", "").toLowerCase();

            Transaction call = Transaction.createEthCallTransaction(
                    walletAddress, omtContractAddress, functionData);

            String result = web3j.ethCall(call, DefaultBlockParameterName.LATEST)
                    .send().getValue();

            if (result == null || result.equals("0x") || result.length() <= 2) return 0.0;

            BigInteger omtWei = new BigInteger(result.substring(2), 16);
            BigDecimal omt = new BigDecimal(omtWei)
                    .divide(new BigDecimal("1000000000000000000"));
            log.info("[Wallet] OMT 잔액: {} OMT ({})", omt, walletAddress);
            return omt.doubleValue();
        } catch (Exception e) {
            log.error("[Wallet] OMT 잔액 조회 실패 ({}): {}", walletAddress, e.getMessage());
            return 0.0;
        }
    }
}
