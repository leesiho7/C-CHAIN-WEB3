package com.tem.cchain.wallet.audit;

/**
 * 감사 로그에 기록할 지갑 작업 유형.
 * 각 값은 감사 레코드의 operation_type 컬럼에 저장됩니다.
 */
public enum WalletOperation {

    // 전송 작업
    MASTER_TRANSFER("마스터 지갑에서 전송"),
    REWARD_PAYMENT("기여 보상 지급"),

    // 조회 작업
    BALANCE_CHECK("잔액 조회"),
    ADDRESS_LOOKUP("주소 조회"),

    // 보안 이벤트
    POLICY_DENIED("정책 거부"),
    ROLE_DENIED("역할 접근 거부"),
    KMS_SIGN("KMS 서명 실행"),

    // 시스템
    BALANCE_SYNC("잔액 동기화");

    private final String description;

    WalletOperation(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
