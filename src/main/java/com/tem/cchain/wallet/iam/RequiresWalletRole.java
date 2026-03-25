package com.tem.cchain.wallet.iam;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 지갑 메서드에 역할 기반 접근 제어를 적용하는 어노테이션.
 *
 * ---- 사용 예시 ----
 *
 * @RequiresWalletRole(WalletRole.MASTER)
 * public String transferFromMaster(...) { ... }
 *
 * @RequiresWalletRole(value = WalletRole.REWARD, minLevel = true)
 * public String rewardContribution(...) { ... }
 *
 * ---- 동작 방식 ----
 * WalletRoleAspect(AOP)가 이 어노테이션이 붙은 메서드 호출을 가로채서
 * 현재 SecurityContext의 사용자 역할을 확인합니다.
 * 권한이 없으면 WalletAccessDeniedException을 던집니다.
 *
 * @Target(METHOD)  → 메서드에만 적용
 * @Retention(RUNTIME) → 런타임에 AOP가 읽을 수 있도록
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresWalletRole {

    /**
     * 이 메서드 실행에 필요한 최소 역할.
     */
    WalletRole value();

    /**
     * true: value 이상의 역할도 허용 (계층적 권한)
     * false: 정확히 value 역할만 허용 (엄격한 매칭)
     * 기본값: true (계층적 허용)
     */
    boolean minLevel() default true;
}
