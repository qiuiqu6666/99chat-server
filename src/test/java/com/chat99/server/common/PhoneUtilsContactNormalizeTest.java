package com.chat99.server.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PhoneUtilsContactNormalizeTest {

    private final PhoneUtils phoneUtils = new PhoneUtils();

    @Test
    void normalizeContactPhone_stripsFormatting() {
        assertThat(phoneUtils.normalizeContactPhone("138 0013-8000", "CN"))
            .isEqualTo("+8613800138000");
        assertThat(phoneUtils.normalizeContactPhone("(138)00138000", "CN"))
            .isEqualTo("+8613800138000");
    }

    @Test
    void normalizeContactPhone_convertsLeadingDoubleZero() {
        assertThat(phoneUtils.normalizeContactPhone("008613800138000", "CN"))
            .isEqualTo("+8613800138000");
    }

    @Test
    void normalizeContactPhone_prependsChinaCodeForElevenDigitMobile() {
        assertThat(phoneUtils.normalizeContactPhone("13800138000", "CN"))
            .isEqualTo("+8613800138000");
    }

    @Test
    void normalizeContactPhone_keepsE164() {
        assertThat(phoneUtils.normalizeContactPhone("+8613800138000", "CN"))
            .isEqualTo("+8613800138000");
    }

    @Test
    void normalizeContactPhone_rejectsBlank() {
        assertThatThrownBy(() -> phoneUtils.normalizeContactPhone("  ", "CN"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
