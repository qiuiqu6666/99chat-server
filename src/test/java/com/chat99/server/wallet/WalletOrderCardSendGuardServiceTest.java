package com.chat99.server.wallet;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.chat99.server.im.ImUserIdService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.StringRedisTemplate;

class WalletOrderCardSendGuardServiceTest {
    final ObjectMapper json = new ObjectMapper();
    final WalletRedPacketRepository packets = mock(WalletRedPacketRepository.class);
    final WalletTransferRepository transfers = mock(WalletTransferRepository.class);
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final WalletOrderCardSendGuardService guard = new WalletOrderCardSendGuardService(
        new WalletOrderCardGuardProperties(false, false, true, 90, List.of("administrator")),
        packets, transfers, mock(ImUserIdService.class), redis, json);

    Map<String, Object> body(String data) {
        return Map.of("From_Account", "administrator", "GroupId", "g1", "MsgBody", List.of(
            Map.of("MsgType", "TIMCustomElem", "MsgContent", Map.of("Data", data))));
    }
    @ParameterizedTest @ValueSource(strings = {
        "{\"type\":\"wallet_transfer\",\"orderId\":\"9999999\"}",
        "{\"type\":\"wallet_red_packet\",\"orderId\":\"1\",\"amount\":1000000}",
        "{\"type\":\"wallet_group_transfer\",\"serverManagedCard\":true}",
        "{\"businessID\":\"wallet_order\"}",
        "{\"customType\":\"text\",\"type\":\"wallet_red_packet\"}",
        "{\"customType\":\"wallet_transfer\",\"type\":\"text\"}",
        "{\"type\":\" WALLET_RED_PACKET \"}",
        "{\"businessId\":\"wallet_order\",\"clientOrderId\":\"copied-order\"}"
    })
    void rejectsClientFinancialCardsWithoutDatabaseOrRedis(String payload) {
        assertThat(guard.isEnabled()).isTrue();
        assertThat(guard.evaluate(body(payload))).contains("WALLET_CARD_SERVER_MANAGED");
        verifyNoInteractions(packets, transfers, redis);
    }
    @Test void doubleEncodedAndMultiElementCardsCannotBypassGuard() throws Exception {
        String payload = json.writeValueAsString("{\"type\":\"wallet_red_packet\"}");
        assertThat(guard.evaluate(body(payload))).isPresent();
        var mixed = new HashMap<>(body("{}"));
        mixed.put("MsgBody", List.of(Map.of("MsgType", "TIMTextElem", "MsgContent", Map.of("Text", "hello")),
            ((List<?>) body(payload).get("MsgBody")).get(0)));
        assertThat(guard.evaluate(mixed)).isPresent();
    }
    @Test void ordinaryMessagesAndClaimNoticesAreUnaffected() {
        assertThat(guard.evaluate(body("{\"type\":\"contact_card\"}"))).isEmpty();
        assertThat(guard.evaluate(body("{\"businessID\":\"red_packet_claim_notice\"}"))).isEmpty();
        assertThat(guard.evaluate(body("not-json"))).isEmpty();
        assertThat(guard.evaluate(Map.of("MsgBody", List.of(Map.of("MsgType", "TIMTextElem",
            "MsgContent", Map.of("Text", "wallet_red_packet")))))).isEmpty();
    }
}
