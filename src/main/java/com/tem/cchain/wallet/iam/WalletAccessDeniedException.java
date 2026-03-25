package com.tem.cchain.wallet.iam;

/**
 * 지갑 역할 검증 실패 시 발생하는 예외.
 * 403 Forbidden 응답으로 처리됩니다.
 */
public class WalletAccessDeniedException extends RuntimeException {

    private final String requiredRole;
    private final String actualRole;

    public WalletAccessDeniedException(WalletRole required, String actual) {
        super(String.format("지갑 접근 거부: 필요 역할=%s, 현재 역할=%s", required, actual));
        this.requiredRole = required.name();
        this.actualRole = actual;
    }

    public String getRequiredRole() { return requiredRole; }
    public String getActualRole() { return actualRole; }
}
