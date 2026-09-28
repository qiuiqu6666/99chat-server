package com.chat99.server.im;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class WalletCardImBodyTest {
    @Test void c2cSynchronizesSenderAndPreservesAfterSendCallbacks() {
        var body = ImAdminClient.walletCardBody(false, "u1", "u2", 1234, 1234567, "{}");
        assertThat(body.get("SyncOtherMachine")).isEqualTo(1);
        assertThat(body.get("MsgRandom")).isEqualTo(1234);
        assertThat(body.get("ForbidCallbackControl")).isEqualTo(List.of("ForbidBeforeSendMsgCallback"));
        assertThat(body).doesNotContainKey("OnlineOnlyFlag");
    }
    @Test void groupUsesOriginalSenderAndStableRandom() {
        var body = ImAdminClient.walletCardBody(true, "u1", "g1", 1234, 1234567, "{}");
        assertThat(body).containsEntry("GroupId", "g1").containsEntry("From_Account", "u1").containsEntry("Random", 1234);
    }
}
