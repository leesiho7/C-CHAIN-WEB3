package com.tem.cchain.web3config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
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
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public Web3j web3j() {
        if ("none".equals(rpcUrl) || rpcUrl == null || rpcUrl.isEmpty()) {
            System.err.println("⚠️ [Web3Config] rpcUrl이 'none'이거나 비어있습니다. Web3j 빈이 생성되지 않습니다.");
            return null;
        }
        try {
            return Web3j.build(new HttpService(rpcUrl));
        } catch (Exception e) {
            System.err.println("⚠️ [Web3Config] Web3j 연결 실패: " + e.getMessage());
            return null;
        }
    }

    /**
     * MyToken 빈을 생성할 때 TransactionManager를 직접 내부에서 생성하여 주입합니다.
     */
    @Bean
    public MyToken myToken(Web3j web3j) {
        if (web3j == null || "none".equals(contractAddress) || "none".equals(privateKey)) {
            System.err.println("⚠️ [Web3Config] Web3j가 null이거나 contractAddress/privateKey가 'none'입니다. MyToken 빈이 생성되지 않습니다.");
            return null;
        }
        
        try {
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
        } catch (Exception e) {
            System.err.println("⚠️ [Web3Config] MyToken 로드 실패: " + e.getMessage());
            return null;
        }
    }
}
