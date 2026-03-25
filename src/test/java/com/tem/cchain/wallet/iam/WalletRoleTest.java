package com.tem.cchain.wallet.iam;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * WalletRole 역할 계층 테스트.
 *
 * ---- 검증 시나리오 ----
 * MASTER >= MASTER, REWARD, READ_ONLY (모두 통과)
 * REWARD >= REWARD, READ_ONLY         (통과)
 * REWARD < MASTER                     (실패)
 * READ_ONLY >= READ_ONLY              (통과)
 * READ_ONLY < REWARD, MASTER          (실패)
 */
@DisplayName("WalletRole 권한 계층 테스트")
class WalletRoleTest {

    @Test
    @DisplayName("MASTER는 모든 역할 수준 이상이다")
    void master_hasAtLeastAll() {
        assertThat(WalletRole.MASTER.hasAtLeast(WalletRole.MASTER)).isTrue();
        assertThat(WalletRole.MASTER.hasAtLeast(WalletRole.REWARD)).isTrue();
        assertThat(WalletRole.MASTER.hasAtLeast(WalletRole.READ_ONLY)).isTrue();
    }

    @Test
    @DisplayName("REWARD는 MASTER 미만이다")
    void reward_belowMaster() {
        assertThat(WalletRole.REWARD.hasAtLeast(WalletRole.MASTER)).isFalse();
        assertThat(WalletRole.REWARD.hasAtLeast(WalletRole.REWARD)).isTrue();
        assertThat(WalletRole.REWARD.hasAtLeast(WalletRole.READ_ONLY)).isTrue();
    }

    @Test
    @DisplayName("READ_ONLY는 REWARD와 MASTER 미만이다")
    void readOnly_lowestLevel() {
        assertThat(WalletRole.READ_ONLY.hasAtLeast(WalletRole.MASTER)).isFalse();
        assertThat(WalletRole.READ_ONLY.hasAtLeast(WalletRole.REWARD)).isFalse();
        assertThat(WalletRole.READ_ONLY.hasAtLeast(WalletRole.READ_ONLY)).isTrue();
    }
}
