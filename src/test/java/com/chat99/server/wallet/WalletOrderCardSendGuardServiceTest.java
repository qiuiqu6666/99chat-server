package com.chat99.server.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.im.ImUserIdService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class WalletOrderCardSendGuardServiceTest {

    @Mock WalletRedPacketRepository redPacketRepository;
    @Mock WalletTransferRepository transferRepository;
    @Mock ImUserIdService imUserIdService;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;

    private WalletOrderCardSendGuardService service;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        var props = new WalletOrderCardGuardProperties(true, true, false, 90, java.util.List.of("administrator"));
        service = new WalletOrderCardSendGuardService(
            props, redPacketRepository, transferRepository, imUserIdService, redis, new ObjectMapper());
    }

    @Test
    void rejectsUnknownOrder() {
        when(imUserIdService.findBusinessUserId("u1")).thenReturn(Optional.of("u1"));
        when(redPacketRepository.findById(999L)).thenReturn(Optional.empty());

        Optional<String> reject = service.evaluate(groupBody("u1", "g1", cardJson(999, "u1", 100, "99")));
        assertThat(reject).contains("WALLET_CARD_NOT_FOUND");
        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void rejectsSenderMismatch() {
        when(imUserIdService.findBusinessUserId("attacker")).thenReturn(Optional.of("attacker"));
        WalletRedPacket p = packet(368, "owner", "g1", 188800L, WalletCurrency.PLATFORM);
        when(redPacketRepository.findById(368L)).thenReturn(Optional.of(p));

        Optional<String> reject = service.evaluate(groupBody("attacker", "g1", cardJson(368, "owner", 188800, "99")));
        assertThat(reject).contains("WALLET_CARD_SENDER_MISMATCH");
    }

    @Test
    void rejectsWrongGroup() {
        when(imUserIdService.findBusinessUserId("owner")).thenReturn(Optional.of("owner"));
        WalletRedPacket p = packet(368, "owner", "g1", 188800L, WalletCurrency.PLATFORM);
        when(redPacketRepository.findById(368L)).thenReturn(Optional.of(p));

        Optional<String> reject = service.evaluate(groupBody("owner", "other-group", cardJson(368, "owner", 188800, "99")));
        assertThat(reject).contains("WALLET_CARD_CONV_MISMATCH");
    }

    @Test
    void rejectsAmountTamper() {
        when(imUserIdService.findBusinessUserId("owner")).thenReturn(Optional.of("owner"));
        WalletRedPacket p = packet(368, "owner", "g1", 188800L, WalletCurrency.PLATFORM);
        when(redPacketRepository.findById(368L)).thenReturn(Optional.of(p));

        Optional<String> reject = service.evaluate(groupBody("owner", "g1", cardJson(368, "owner", 1, "99")));
        assertThat(reject).contains("WALLET_CARD_PAYLOAD_MISMATCH");
    }

    @Test
    void allowsFirstSendThenRejectsDup() {
        when(imUserIdService.findBusinessUserId("owner")).thenReturn(Optional.of("owner"));
        WalletRedPacket p = packet(368, "owner", "g1", 188800L, WalletCurrency.PLATFORM);
        when(redPacketRepository.findById(368L)).thenReturn(Optional.of(p));
        when(valueOps.setIfAbsent(eq("im:wallet-card:group:g1:rp:368"), eq("1"), any(Duration.class)))
            .thenReturn(true)
            .thenReturn(false);

        Map<String, Object> body = groupBody("owner", "g1", cardJson(368, "owner", 188800, "99"));
        assertThat(service.evaluate(body)).isEmpty();
        assertThat(service.evaluate(body)).contains("WALLET_CARD_DUP");
    }

    @Test
    void allowlistedAdminBypasses() {
        when(imUserIdService.isSpecialImAccount("administrator")).thenReturn(true);

        Optional<String> reject = service.evaluate(
            groupBody("administrator", "g1", cardJson(368, "x", 1, "99")));
        assertThat(reject).isEmpty();
        verify(redPacketRepository, never()).findById(any());
    }

    private static WalletRedPacket packet(long id, String sender, String groupId, long amount, WalletCurrency c) {
        WalletRedPacket p = new WalletRedPacket();
        p.setId(id);
        p.setSenderUserId(sender);
        p.setGroupId(groupId);
        p.setConversationType("GROUP");
        p.setTotalAmount(amount);
        p.setCurrency(c);
        p.setPacketType(RedPacketType.LUCKY_GROUP);
        p.setStatus(RedPacketStatus.ACTIVE);
        return p;
    }

    private static Map<String, Object> groupBody(String from, String groupId, String dataJson) {
        return Map.of(
            "From_Account", from,
            "GroupId", groupId,
            "MsgBody", java.util.List.of(Map.of(
                "MsgType", "TIMCustomElem",
                "MsgContent", Map.of("Data", dataJson)
            ))
        );
    }

    private static String cardJson(long orderId, String sender, long amount, String currency) {
        return """
            {"businessID":"wallet_order","type":"wallet_red_packet","orderId":%d,"senderUserId":"%s","amount":%d,"currency":"%s"}
            """.formatted(orderId, sender, amount, currency).trim();
    }
}
