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

    private final ObjectProvider<Web3j> web3jProvider;

    @Value("${token.contract.address:none}")
    private String omtContractAddress;

    public double getEthBalance(String walletAddress) {
        Web3j web3j = web3jProvider.getIfAvailable();
        if (web3j == null || walletAddress == null || walletAddress.isBlank()) return 0.0;
        try {
            BigInteger wei = web3j.ethGetBalance(walletAddress, DefaultBlockParameterName.LATEST).send().getBalance();
            BigDecimal eth = Convert.fromWei(wei.toString(), Convert.Unit.ETHER);
            return eth.doubleValue();
        } catch (Exception e) {
            log.error("[Wallet] ETH 잔액 조회 실패: {}", e.getMessage());
            return 0.0;
        }
    }

    public double getOmtBalance(String walletAddress) {
        Web3j web3j = web3jProvider.getIfAvailable();
        if (web3j == null || walletAddress == null || walletAddress.isBlank() || "none".equals(omtContractAddress)) return 0.0;
        try {
            String functionData = "0x70a08231000000000000000000000000" + walletAddress.replace("0x", "").toLowerCase();
            Transaction call = Transaction.createEthCallTransaction(walletAddress, omtContractAddress, functionData);
            String result = web3j.ethCall(call, DefaultBlockParameterName.LATEST).send().getValue();
            if (result == null || result.equals("0x")) return 0.0;
            BigInteger omtWei = new BigInteger(result.substring(2), 16);
            return new BigDecimal(omtWei).divide(new BigDecimal("1000000000000000000")).doubleValue();
        } catch (Exception e) {
            log.error("[Wallet] OMT 잔액 조회 실패: {}", e.getMessage());
            return 0.0;
        }
    }
}