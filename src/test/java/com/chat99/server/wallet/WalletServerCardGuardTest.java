package com.chat99.server.wallet;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.chat99.server.im.ImUserIdService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class WalletServerCardGuardTest {
    @Test void serverOwnershipIsEnforcedEvenWithLegacyGuardDisabledOrLogOnly() {
        for (boolean enabled : List.of(false, true)) {
            var packets = mock(WalletRedPacketRepository.class);
            var outbox = mock(WalletCardOutboxRepository.class);
            var redis = mock(StringRedisTemplate.class);
            var service = new WalletOrderCardSendGuardService(new WalletOrderCardGuardProperties(enabled, false, true, 90, List.of()),
                packets, mock(WalletTransferRepository.class), mock(ImUserIdService.class), redis, new ObjectMapper());
            var packet = new WalletRedPacket(); packet.setId(7L);
            when(packets.findById(7L)).thenReturn(Optional.of(packet));
            when(outbox.existsById("rp:7")).thenReturn(true);
            var body = Map.<String, Object>of("From_Account", "administrator", "MsgBody", List.of(Map.of(
                "MsgType", "TIMCustomElem", "MsgContent", Map.of("Data", "{\"type\":\"wallet_red_packet\",\"orderId\":\"7\"}"))));
            assertThat(service.isEnabled()).isTrue();
            assertThat(service.evaluate(body)).contains("WALLET_CARD_SERVER_MANAGED");
            verifyNoInteractions(redis);
        }
    }
}
