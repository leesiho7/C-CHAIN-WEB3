項目概述
  C-CHAIN-WEB3 係一套針對香港 Web3 生態系統開發嘅高性能、高安全性錢包服務。我哋明白交易所對「安全」同「合規」嘅嚴苛要求，所以成個系統由底層開始就採用咗 AWS HSM (Hardware Security Module)
  加密保護，保證私鑰喺任何時候都唔會離開硬體安全模組。

  技術核心亮點
   1. AWS KMS & HSM 深度集成：
       * 私鑰不落地：系統使用 AWS KMS (ECC_SECG_P256K1) 進行交易簽名。私鑰喺 HSM 內部產生同儲存，應用程式、作業系統甚至 AWS 管理員都無法讀取私鑰明文。
       * AWS CLI 自動化管理：透過 AWS CLI 同 SDK 實現加密金鑰嘅生命週期管理（Rotation, Access Control）。
   2. 零信任架構 (Zero Trust Build)：
       * OPA (Open Policy Agent) 引擎：所有提幣同交易請求都必須經過 OPA 策略引擎審核。我哋實施咗 Fail-Close 原則，任何未知或異常請求都會被即時拒絕。
       * 細粒度權限控制 (RBAC)：定義咗 MASTER、REWARD、READ_ONLY 等角色，嚴格遵守「最小權限原則（Least Privilege）」。
   3. 實時風控與監控 (FDS & Monitoring)：
       * Real-time FDS：內置欺詐檢測系統，針對異常大額提現、頻繁操作進行 AI 分析同攔截。
       * 區塊鏈監控：實時監測鏈上交易狀態，確保資產對賬準確無誤。

 项目概述
  C-CHAIN-WEB3 是为 Web3 交易所及合规金融机构打造的企業级数字资产托管系统。本项目专注于解决 Web3 核心的安全痛点，通过 零信任（Zero Trust） 架构与 AWS KMS 的深度融合，为数字资产提供银行级的安全保障。

  核心安全技术
   1. 基于 AWS KMS 的私钥安全管理：
       * 硬件级保护：利用 AWS KMS 提供的 FIPS 140-2 Level 3 认证硬件安全模块（HSM）管理私钥。
       * 无感签名：交易签名过程完全在 HSM 内部完成，彻底消除了私钥在内存中泄露的风险。
   2. 动态策略引擎 (Policy Engine)：
       * 集成 Open Policy Agent (OPA)，支持基于 Rego 语言的复杂业务逻辑验证（如：每日限额、IP 白名单、多重身份核驗）。
   3. 零信任安全构建：
       * 采用 IAM 角色分离 与 安全审计日志，确保每一笔操作都有迹可循，满足香港 SFC 等监管机构的审计要求。
   4. 高性能索引与同步：
       * 自研高性能區塊鏈索引器（Indexer），支持海量交易數據的實時處理與異步落庫。

 * Backend: Java 17, Spring Boot 3.x
   * Web3: Web3j, Ethereum/EVM Compatibility
   * Security: AWS SDK (KMS, Secrets Manager), OPA (Open Policy Agent)
   * Database: MySQL (MyBatis/JPA), Redis (Caching)
   * DevOps: AWS CLI, Docker, Railway.app
Security Implementation Details

  1. AWS KMS Transaction Signing

   1 // KmsTransactionSigner.java 核心邏輯
   2 // 私鑰永遠唔會出現喺 JVM Memory
   3 SignResponse signResponse = kmsClient.sign(SignRequest.builder()
   4     .keyId(kmsKeyId)
   5     .message(SdkBytes.fromByteArray(transactionHash))
   6     .messageType(MessageType.DIGEST)
   7     .signingAlgorithm(SigningAlgorithmSpec.ECDSA_SHA_256)
   8     .build());

  2. OPA Policy (Zero Trust)

   1 # wallet.rego 範例
   2 default allow = false
   3
   4 allow {
   5     input.caller_role == "MASTER"
   6     input.amount <= 1000
   7     not is_blacklisted(input.to_address)
   8 }






[한국어판] 프로젝트 요약 및 핵심 기술
1. 프로젝트 개요
.이에 따른 보상을 투명하게 지급하는 하이브리드 Web3 보상 인프라입니다.
"신뢰가 최고의 인프라다"라는 철학 아래 데이터의 정합성과 영속성을 보장합니다
핵심 구현 기능
AI 자동 검수 시스템: 제출된 문서의 유효성을 AI가 1차 검수하여 기여의 질을 보장합니다.

Redis 분산락(Distributed Lock): 다중 노드 서버 환경에서 동일 유저의 중복 보상 청구 및 데이터 경합 문제를 원천 차단하여 보상의 유일성을 보장합니다.

하이브리드 데이터 동기화: * On-Chain: 이더리움(Sepolia) 노드에 트랜잭션 해시 및 보상 내역 영구 기록.

Off-Chain: MySQL에 해시 데이터 및 지갑 잔액을 실시간 미러링하여 조회 성능 최적화.

실시간 금융 UI/UX: Bybit API Stream 연동을 통해 초 단위로 자산 가치가 변하는 실시간 시계열 대시보드를 제공합니다.

사용자 친화적 Web3: MetaMask SDK 연동 및 신규 유저를 위한 지갑 자동 생성(EC Key Pair) 기능을 지원합니다.

3. 기술 스택
Backend: Spring Boot, Web3j, WebClient, Redis, MySQL

Blockchain: Solidity, Hardhat, Sepolia Testnet

Precision: BigDecimal을 통한 소수점 18자리 금융 연산 정밀도 확보

