package com.tem.cchain.wallet.iam;

/**
 * 서버 지갑에 접근하는 주체의 역할(Role).
 *
 * ---- IAM Role 설계 원칙 ----
 * 최소 권한 원칙(Principle of Least Privilege):
 * 각 역할은 자신의 업무에 필요한 최소한의 권한만 가집니다.
 *
 * MASTER    → 모든 지갑 작업 가능 (전송, 보상, 조회)
 *             주로 관리자 API 또는 내부 배치 작업에 사용
 *
 * REWARD    → 보상 지급 전용 (transfer 불가, reward만 가능)
 *             기여도 보상 로직이 이 역할로 동작
 *
 * READ_ONLY → 잔액 조회만 가능 (어떠한 전송도 불가)
 *             모니터링, 대시보드, 외부 연동 서비스에 사용
 */
public enum WalletRole {

    MASTER(10),    // 최고 권한
    REWARD(5),     // 보상 전용
    READ_ONLY(1);  // 읽기 전용

    private final int level;

    WalletRole(int level) {
        this.level = level;
    }

    /**
     * 이 역할이 요구 역할 이상의 권한을 가지는지 확인합니다.
     * 예: MASTER.hasAtLeast(REWARD) → true
     *     READ_ONLY.hasAtLeast(REWARD) → false
     */
    public boolean hasAtLeast(WalletRole required) {
        return this.level >= required.level;
    }
}
