package com.chat99.server.robot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class MachineCodeSupportTest {

    @Test
    void generateHasCanonicalShape() {
        String code = MachineCodeSupport.generate();
        assertThat(code).matches("[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}");
    }

    @Test
    void canonicalizeNewFormat() {
        assertThat(MachineCodeSupport.canonicalize("abcd-efgh-jkmn")).isEqualTo("ABCD-EFGH-JKMN");
        assertThat(MachineCodeSupport.canonicalize("ABCDEFGHJKMN")).isEqualTo("ABCD-EFGH-JKMN");
    }

    @Test
    void canonicalizeLegacyOpaque() {
        assertThat(MachineCodeSupport.canonicalize("@2HGQG6M5CD")).isEqualTo("@2HGQG6M5CD");
    }

    @Test
    void rejectBlank() {
        assertThatThrownBy(() -> MachineCodeSupport.canonicalize(" "))
            .isInstanceOf(ResponseStatusException.class);
    }
}
