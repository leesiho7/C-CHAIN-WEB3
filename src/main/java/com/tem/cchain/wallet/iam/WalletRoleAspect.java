package com.tem.cchain.wallet.iam;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * @RequiresWalletRole 어노테이션을 처리하는 AOP Aspect.
 *
 * ---- AOP(Aspect-Oriented Programming) 쉽게 이해하기 ----
 * 메서드 A를 호출하면:
 *   1. Spring이 "잠깐, 이 메서드에 @RequiresWalletRole 있네?"
 *   2. 이 Aspect가 가로채서 역할 검증 먼저 실행
 *   3. 통과하면 실제 메서드 A 실행
 *   4. 실패하면 WalletAccessDeniedException 던짐
 *
 * 서비스 코드에 역할 검증 로직이 전혀 없어도 됩니다.
 * 관심사 분리(Separation of Concerns)의 핵심 예시입니다.
 *
 * ---- @Around 포인트컷 설명 ----
 * "@annotation(com.tem.cchain.wallet.iam.RequiresWalletRole)"
 * → 이 어노테이션이 붙은 모든 메서드에 적용
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class WalletRoleAspect {

    private final IamRoleService iamRoleService;

    @Around("@annotation(com.tem.cchain.wallet.iam.RequiresWalletRole)")
    public Object checkWalletRole(ProceedingJoinPoint joinPoint) throws Throwable {
        // 어노테이션 정보 추출
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        RequiresWalletRole annotation = method.getAnnotation(RequiresWalletRole.class);

        WalletRole requiredRole = annotation.value();
        boolean minLevel = annotation.minLevel();

        // 현재 사용자 역할 조회
        WalletRole currentRole = iamRoleService.getCurrentRole();

        boolean permitted = minLevel
            ? currentRole.hasAtLeast(requiredRole)     // 계층적: 이상이면 허용
            : currentRole == requiredRole;             // 엄격: 정확히 일치

        if (!permitted) {
            log.warn("[IAM] 접근 거부: method={}, required={}, actual={}",
                method.getName(), requiredRole, currentRole);
            throw new WalletAccessDeniedException(requiredRole, currentRole.name());
        }

        log.debug("[IAM] 접근 허용: method={}, role={}", method.getName(), currentRole);
        return joinPoint.proceed();  // 실제 메서드 실행
    }
}
