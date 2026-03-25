package com.tem.cchain.wallet.iam;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.function.Supplier;

/**
 * 서버 내부 서비스 호출 시 임시로 WalletRole을 설정하는 유틸리티.
 *
 * ---- 왜 필요한가? ----
 * @RequiresWalletRole AOP는 Spring SecurityContext에서 역할을 읽습니다.
 * HTTP 요청(유저 로그인)이 아닌 서버 내부 호출(스케줄러, ContributionService 등)은
 * SecurityContext가 비어있어 기본값(READ_ONLY)이 적용됩니다.
 *
 * 이 유틸리티로 내부 호출 시 명시적으로 역할을 부여합니다.
 *
 * ---- 사용 예시 ----
 * String txHash = SecurityContextUtil.runWith(WalletRole.REWARD, "system-reward",
 *     () -> enterpriseWalletService.payReward(address, amount)
 * );
 */
public class SecurityContextUtil {

    private SecurityContextUtil() {}

    /**
     * 지정한 WalletRole로 SecurityContext를 임시 설정하고 작업을 실행합니다.
     * 실행 후에는 이전 컨텍스트를 복원합니다.
     *
     * @param role      부여할 역할
     * @param principal 호출 주체 이름 (감사 로그에 기록됨)
     * @param action    실행할 작업 (반환값 있음)
     * @return 작업 결과
     */
    public static <T> T runWith(WalletRole role, String principal, Supplier<T> action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        try {
            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(new UsernamePasswordAuthenticationToken(
                principal,
                null,
                Collections.singletonList(
                    new SimpleGrantedAuthority("ROLE_WALLET_" + role.name())
                )
            ));
            SecurityContextHolder.setContext(ctx);
            return action.get();
        } finally {
            // 반드시 이전 컨텍스트 복원 (스레드 풀 재사용 환경에서 누수 방지)
            SecurityContextHolder.setContext(previous);
        }
    }

    /**
     * 반환값이 없는 작업용 오버로드.
     */
    public static void runWith(WalletRole role, String principal, Runnable action) {
        runWith(role, principal, () -> {
            action.run();
            return null;
        });
    }
}
