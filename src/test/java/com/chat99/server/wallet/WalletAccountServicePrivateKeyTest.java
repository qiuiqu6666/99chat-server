package com.chat99.server.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WalletAccountServicePrivateKeyTest {

    @Test
    void normalizePrivateKeyHex_padsAndLowercases() {
        assertThat(WalletAccountService.normalizePrivateKeyHex("AB"))
            .isEqualTo("00000000000000000000000000000000000000000000000000000000000000ab");
    }

    @Test
    void normalizePrivateKeyHex_strips0x() {
        assertThat(WalletAccountService.normalizePrivateKeyHex(
            "0x1111111111111111111111111111111111111111111111111111111111111111"))
            .isEqualTo("1111111111111111111111111111111111111111111111111111111111111111");
    }

    @Test
    void normalizePrivateKeyHex_rejectsInvalid() {
        assertThatThrownBy(() -> WalletAccountService.normalizePrivateKeyHex("zz"))
            .isInstanceOf(IllegalStateException.class);
    }
}
