package com.chat99.server.im;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class WalletCardUpdateBodyTest {
    @Test void groupUpdateUsesOriginalSequenceWithoutSendRandom() {
        var body = ImAdminClient.walletCardUpdateBody(true, "u1", "g1", null, 88L, "{}");
        assertThat(body).containsEntry("GroupId", "g1").containsEntry("MsgSeq", 88L)
            .containsKey("MsgBody").doesNotContainKeys("Random", "MsgRandom", "To_Account");
    }
    @Test void c2cUpdateUsesSenderRecipientAndMessageKey() {
        var body = ImAdminClient.walletCardUpdateBody(false, "u1", "u2", "key", null, "{}");
        assertThat(body).containsEntry("From_Account", "u1").containsEntry("To_Account", "u2")
            .containsEntry("MsgKey", "key").doesNotContainKeys("MsgSeq", "Random", "GroupId");
    }
    @Test void refusesMissingLocator() {
        assertThatIllegalArgumentException().isThrownBy(() -> ImAdminClient.walletCardUpdateBody(true, "u1", "g", null, null, "{}"));
        assertThatIllegalArgumentException().isThrownBy(() -> ImAdminClient.walletCardUpdateBody(false, "u1", "u2", "", null, "{}"));
    }
}
