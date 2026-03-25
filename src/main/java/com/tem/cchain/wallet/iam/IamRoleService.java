package com.tem.cchain.wallet.iam;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Spring Security Context에서 현재 사용자의 WalletRole을 조회합니다.
 *
 * ---- 실제 운영에서의 흐름 ----
 * 1. EC2 인스턴스에 IAM Role "wallet-master-role" 부여
 * 2. Spring Security가 JWT 또는 API Key로 인증 → Authentication 객체에 역할 저장
 * 3. 이 서비스가 SecurityContext에서 역할을 읽어 WalletRole로 변환
 *
 * ---- AWS IAM과의 연동 아이디어 ----
 * JWT의 claims에 IAM Role ARN을 넣거나,
 * API Key를 AWS Secrets Manager에 저장하고 역할과 매핑하는 방식으로 확장 가능
 */
@Slf4j
@Service
public class IamRoleService {

    private static final String WALLET_ROLE_PREFIX = "ROLE_WALLET_";

    /**
     * 현재 SecurityContext의 사용자 역할을 WalletRole로 변환합니다.
     *
     * Spring Security의 Authority 네이밍 규칙:
     * ROLE_WALLET_MASTER   → WalletRole.MASTER
     * ROLE_WALLET_REWARD   → WalletRole.REWARD
     * ROLE_WALLET_READ_ONLY → WalletRole.READ_ONLY
     *
     * @return WalletRole, 없으면 READ_ONLY (최소 권한 기본값)
     */
    public WalletRole getCurrentRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !auth.isAuthenticated()) {
            log.warn("[IAM] 인증 정보 없음, READ_ONLY 기본값 적용");
            return WalletRole.READ_ONLY;
        }

        return auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .filter(a -> a.startsWith(WALLET_ROLE_PREFIX))
            .map(a -> a.substring(WALLET_ROLE_PREFIX.length()))
            .map(this::parseRole)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .max((a, b) -> a.hasAtLeast(b) ? 1 : -1)  // 가장 높은 역할 선택
            .orElse(WalletRole.READ_ONLY);
    }

    /**
     * 현재 사용자가 요구 역할을 가지는지 확인합니다.
     */
    public boolean hasRole(WalletRole required) {
        WalletRole current = getCurrentRole();
        return current.hasAtLeast(required);
    }

    private Optional<WalletRole> parseRole(String roleName) {
        try {
            return Optional.of(WalletRole.valueOf(roleName));
        } catch (IllegalArgumentException e) {
            log.debug("[IAM] 알 수 없는 역할 무시: {}", roleName);
            return Optional.empty();
        }
    }
}
