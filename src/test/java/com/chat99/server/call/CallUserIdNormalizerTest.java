package com.chat99.server.call;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CallUserIdNormalizerTest {

    @Test
    void normalizeStripsCompositeId() {
        assertThat(CallUserIdNormalizer.normalize("acnj6oxey9#0#0#rqwm8onw3j"))
            .isEqualTo("acnj6oxey9");
    }

    @Test
    void normalizeStripsC2cPrefixAndCompositeId() {
        assertThat(CallUserIdNormalizer.normalize("c2c_acnj6oxey9#0#0#rqwm8onw3j"))
            .isEqualTo("acnj6oxey9");
    }

    @Test
    void normalizeKeepsPlainUserId() {
        assertThat(CallUserIdNormalizer.normalize("rqwm8onw3j")).isEqualTo("rqwm8onw3j");
    }

    @Test
    void normalizeBlankInputs() {
        assertThat(CallUserIdNormalizer.normalize(null)).isEmpty();
        assertThat(CallUserIdNormalizer.normalize("")).isEmpty();
        assertThat(CallUserIdNormalizer.normalize("   ")).isEmpty();
    }

    @Test
    void normalizeDoesNotStripWhenHashAtStart() {
        assertThat(CallUserIdNormalizer.normalize("#bad")).isEqualTo("#bad");
    }

    @Test
    void isValidVoipDisplayNameAcceptsNickname() {
        assertThat(CallUserIdNormalizer.isValidVoipDisplayName("张三", "acnj6oxey9")).isTrue();
    }

    @Test
    void isValidVoipDisplayNameRejectsBlankHashAndCallerIdVariants() {
        assertThat(CallUserIdNormalizer.isValidVoipDisplayName(null, "acnj6oxey9")).isFalse();
        assertThat(CallUserIdNormalizer.isValidVoipDisplayName("  ", "acnj6oxey9")).isFalse();
        assertThat(CallUserIdNormalizer.isValidVoipDisplayName("a#b", "acnj6oxey9")).isFalse();
        assertThat(CallUserIdNormalizer.isValidVoipDisplayName("acnj6oxey9", "acnj6oxey9")).isFalse();
        assertThat(CallUserIdNormalizer.isValidVoipDisplayName("c2c_acnj6oxey9", "acnj6oxey9")).isFalse();
    }

    @Test
    void wasCompositeIdDetectsNormalization() {
        assertThat(CallUserIdNormalizer.wasCompositeId("acnj6oxey9#0#0#rqwm8onw3j", "acnj6oxey9"))
            .isTrue();
        assertThat(CallUserIdNormalizer.wasCompositeId("acnj6oxey9", "acnj6oxey9")).isFalse();
        assertThat(CallUserIdNormalizer.wasCompositeId(null, "acnj6oxey9")).isFalse();
    }
}
