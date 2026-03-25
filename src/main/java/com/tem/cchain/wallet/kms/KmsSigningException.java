package com.tem.cchain.wallet.kms;

/**
 * KMS 서명 과정에서 발생하는 예외.
 * RuntimeException을 상속하므로 checked exception 처리 강제 없음.
 */
public class KmsSigningException extends RuntimeException {

    public KmsSigningException(String message) {
        super(message);
    }

    public KmsSigningException(String message, Throwable cause) {
        super(message, cause);
    }
}
