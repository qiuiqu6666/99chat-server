package com.chat99.server.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class WalletRedPacketJsonViewTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void groupTransferSerializesImAlignedAliases() throws Exception {
        WalletRedPacket p = new WalletRedPacket();
        p.setId(368L);
        p.setPublicId("red_packet_demo");
        p.setSenderUserId("sender01");
        p.setPacketType(RedPacketType.GROUP_TRANSFER);
        p.setConversationType("GROUP");
        p.setGroupId("m25KMR3N5CY");
        p.setExclusiveUserId("peer000001");
        p.setCurrency(WalletCurrency.USDT);
        p.setTotalAmount(1_000_000L);
        p.setPacketCount(1);
        p.setRemainingAmount(0);
        p.setRemainingCount(0);
        p.setStatus(RedPacketStatus.COMPLETED);

        JsonNode n = mapper.readTree(mapper.writeValueAsString(p));
        assertThat(n.path("orderId").asLong()).isEqualTo(368L);
        assertThat(n.path("amount").asLong()).isEqualTo(1_000_000L);
        assertThat(n.path("totalAmount").asLong()).isEqualTo(1_000_000L);
        assertThat(n.path("toUserId").asText()).isEqualTo("peer000001");
        assertThat(n.path("exclusiveUserId").asText()).isEqualTo("peer000001");
        assertThat(n.path("groupId").asText()).isEqualTo("m25KMR3N5CY");
        assertThat(n.path("chatGroupId").asText()).isEqualTo("m25KMR3N5CY");
        assertThat(n.path("imGroupId").asText()).isEqualTo("m25KMR3N5CY");
        assertThat(n.path("senderUserId").asText()).isEqualTo("sender01");
        assertThat(n.path("fromUserId").asText()).isEqualTo("sender01");
        assertThat(n.path("clientOrderId").asText()).isEqualTo("red_packet_demo");
        assertThat(n.path("currency").asText()).isEqualTo("USDT");
        assertThat(n.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(n.path("displayTitle").asText()).isEqualTo("群转账");
        assertThat(n.path("packetType").asText()).isEqualTo("GROUP_TRANSFER");
    }

    @Test
    void luckyGroupOmitsDisplayTitle() throws Exception {
        WalletRedPacket p = new WalletRedPacket();
        p.setId(1L);
        p.setSenderUserId("s");
        p.setPacketType(RedPacketType.LUCKY_GROUP);
        p.setConversationType("GROUP");
        p.setGroupId("g1");
        p.setCurrency(WalletCurrency.PLATFORM);
        p.setTotalAmount(100L);
        p.setPacketCount(2);
        p.setRemainingAmount(100L);
        p.setRemainingCount(2);
        p.setStatus(RedPacketStatus.ACTIVE);

        JsonNode n = mapper.readTree(mapper.writeValueAsString(p));
        assertThat(n.path("currency").asText()).isEqualTo("99");
        assertThat(n.path("toUserId").isNull()).isTrue();
        assertThat(n.path("displayTitle").isMissingNode() || n.path("displayTitle").isNull()).isTrue();
        assertThat(n.path("amount").asLong()).isEqualTo(100L);
    }

    @Test
    void c2cTransferLikePacketOmitsGroupId() throws Exception {
        WalletRedPacket p = new WalletRedPacket();
        p.setId(12L);
        p.setPublicId("red_packet_c2c-0000-0000-0000-000000000001");
        p.setSenderUserId("sender01");
        p.setPacketType(RedPacketType.NORMAL_C2C);
        p.setConversationType("C2C");
        p.setGroupId("should-not-leak");
        p.setExclusiveUserId("peer000001");
        p.setCurrency(WalletCurrency.USDT);
        p.setTotalAmount(500_000L);
        p.setPacketCount(1);
        p.setRemainingAmount(0);
        p.setRemainingCount(0);
        p.setStatus(RedPacketStatus.COMPLETED);
        p.setGreeting("午饭");

        JsonNode n = mapper.readTree(mapper.writeValueAsString(p));
        assertThat(n.path("groupId").isMissingNode() || n.path("groupId").isNull()
            || n.path("groupId").asText().isEmpty()).isTrue();
        assertThat(n.path("chatGroupId").isMissingNode() || n.path("chatGroupId").isNull()).isTrue();
        assertThat(n.path("imGroupId").isMissingNode() || n.path("imGroupId").isNull()).isTrue();
        assertThat(n.path("fromUserId").asText()).isEqualTo("sender01");
        assertThat(n.path("senderUserId").asText()).isEqualTo("sender01");
        assertThat(n.path("amount").isNumber()).isTrue();
        assertThat(n.path("amount").asLong()).isEqualTo(500_000L);
        assertThat(n.path("greeting").asText()).isEqualTo("午饭");
    }
}
