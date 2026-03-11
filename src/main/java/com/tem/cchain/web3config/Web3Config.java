package com.tem.cchain.web3config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.FastRawTransactionManager;
import org.web3j.tx.TransactionManager;
import org.web3j.tx.gas.DefaultGasProvider;
import org.web3j.tx.response.PollingTransactionReceiptProcessor;
import org.web3j.tx.response.TransactionReceiptProcessor;

import com.tem.cchain.contract.MyToken;

@Configuration
public class Web3Config {

    @Value("${ethereum.rpc.url}")
    private String rpcUrl;

    @Value("${token.contract.address}")
    private String contractAddress;

    @Value("${ethereum.wallet.private-key}")
    private String privateKey;

    @Bean
    public Web3j web3j() {
        return Web3j.build(new HttpService(rpcUrl));
    }

    /**
     * MyToken 빈을 생성할 때 TransactionManager를 직접 내부에서 생성하여 주입합니다.
     * 이렇게 하면 Spring의 빈 조회 과정에서의 충돌이나 누락 문제를 완전히 방지할 수 있습니다.
     */
    @Bean
    public MyToken myToken(Web3j web3j) throws Exception {
        // 1. 개인키로 Credentials 생성
        Credentials credentials = Credentials.create(privateKey);
        
        // 2. 영수증 처리기 설정 (1초 간격으로 확인)
        TransactionReceiptProcessor receiptProcessor = new PollingTransactionReceiptProcessor(web3j, 1000, 40);
        
        // 3. FastRawTransactionManager 직접 생성 (Nonce 관리용)
        TransactionManager transactionManager = new FastRawTransactionManager(web3j, credentials, receiptProcessor);
        
        // 4. MyToken 로드
        return MyToken.load(
            contractAddress, 
            web3j, 
            transactionManager, 
            new DefaultGasProvider()
        );
    }
}
