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

1. 项目简介
C-Chain 是一种混合型 Web3 奖励基础设施。它通过 SHA-256 哈希算法将用户的技术贡献锁定在区块链节点上，并确保奖励发放的透明性。秉持“信任即基础设施”的理念，该项目完美实现了数据的一致性与永恒性。

2. 核心功能实现
AI 自动审核系统： 引入 AI 对提交的内容进行初步有效性审查，确保贡献内容的质量。

Redis 分布式锁 (Distributed Lock)： 在多节点服务器环境下，防止同一用户的重复奖励请求及数据竞态问题，确保奖励的唯一性。

混合数据同步： * 链上 (On-Chain)： 在以太坊 (Sepolia) 网络上永久记录交易哈希和奖励详情。

链下 (Off-Chain)： 在 MySQL 中实时镜像哈希数据和钱包余额，优化查询性能。

实时金融 UI/UX： 集成 Bybit API Stream，提供资产价值随秒变化的实时时序数据仪表盘。

用户友好型 Web3： 集成 MetaMask SDK，并为新用户提供自动创建钱包 (EC Key Pair) 的功能，降低准入门槛。

3. 技术栈
后端： Spring Boot, Web3j, WebClient, Redis, MySQL

区块链： Solidity, Hardhat, Sepolia Testnet

精度控制： 通过 BigDecimal 确保 18 位小数的金融级运算精度。
