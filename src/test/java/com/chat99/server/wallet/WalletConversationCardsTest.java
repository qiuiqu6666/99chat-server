package com.chat99.server.wallet;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.chat99.server.group.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class WalletConversationCardsTest extends WalletCardDeliveryTest {
    final GroupAccessService groups = mock(GroupAccessService.class);
    final GroupMemberRepository members = mock(GroupMemberRepository.class);
    WalletConversationCards feed() {
        return new WalletConversationCards(repo, mock(WalletRedPacketRepository.class),
            mock(WalletRedPacketClaimRepository.class), groups, members, json,
            mock(com.chat99.server.im.ImUserIdService.class));
    }
    UsernamePasswordAuthenticationToken auth(String user) {
        return new UsernamePasswordAuthenticationToken(user, "unused", List.of());
    }
    WalletCardOutbox transfer(String id, boolean group) {
        var order = row(group); order.setId("transfer:" + id); order.setClientId("client-" + id);
        order.setPayload("{\"customType\":\"wallet_transfer\",\"senderUserId\":\"u1\",\"orderId\":\"" + id + "\"}");
        return order;
    }
    @Test void committedCardIsVisibleToBothPartiesWithoutAnyImDelivery() {
        save(transfer("1", false));
        var sender = feed().list(auth("u1"), "u2", false, null, "");
        var recipient = feed().list(auth("u2"), "u1", false, null, "");
        assertThat((List<?>) sender.get("cards")).hasSize(1);
        assertThat(recipient.get("cards")).isEqualTo(sender.get("cards"));
        Map<?, ?> card = (Map<?, ?>) ((List<?>) sender.get("cards")).get(0);
        assertThat(card.get("cardDeliveryState")).isEqualTo("PENDING");
        assertThat(card.get("paymentState")).isEqualTo("COMMITTED");
        assertThat(card.get("cardId")).isEqualTo("transfer:1");
        verifyNoInteractions(im);
    }
    @Test void anotherUserCannotReadPrivateCardsAndAnonymousIsRejected() {
        save(transfer("1", false));
        assertThat((List<?>) feed().list(auth("u3"), "u1", false, null, "").get("cards")).isEmpty();
        assertThatThrownBy(() -> feed().list(null, "u2", false, null, ""))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void groupMembershipAndJoinTimeConstrainVisibility() {
        var order = transfer("1", true); order.setCreatedAt(Instant.now().minusSeconds(100)); save(order);
        assertThatThrownBy(() -> feed().list(auth("u3"), "g1", true, null, ""))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        when(groups.isLocalMember("g1", "u3")).thenReturn(true);
        var member = new GroupMember(); member.setJoinedAt(Instant.now());
        when(members.findByGroupIdAndUserIdActive("g1", "u3")).thenReturn(Optional.of(member));
        assertThat((List<?>) feed().list(auth("u3"), "g1", true, null, "").get("cards")).isEmpty();
        member.setJoinedAt(Instant.now().minusSeconds(200));
        assertThat((List<?>) feed().list(auth("u3"), "g1", true, null, "").get("cards")).hasSize(1);
    }
    @Test void sameTimestampPaginationDoesNotLoseOrRepeatCards() {
        Instant same = Instant.now().minusSeconds(20);
        for (int i = 1; i <= 52; i++) {
            var order = transfer(Integer.toString(i), false); order.setCreatedAt(same); save(order);
        }
        var first = feed().list(auth("u1"), "u2", false, null, "");
        var second = feed().list(auth("u1"), "u2", false,
            first.get("nextBefore").toString(), first.get("nextBeforeId").toString());
        assertThat((List<?>) first.get("cards")).hasSize(50);
        assertThat((List<?>) second.get("cards")).hasSize(2);
        Set<Object> ids = new HashSet<>();
        for (var page : List.of(first, second)) for (Object value : (List<?>) page.get("cards"))
            assertThat(ids.add(((Map<?, ?>) value).get("cardId"))).isTrue();
    }

    @Test void conversationReadsCurrentClaimStateEvenWhenImWasNeverUpdated() {
        save(row(false));
        var packets = mock(WalletRedPacketRepository.class);
        var claims = mock(WalletRedPacketClaimRepository.class);
        var packet = new WalletRedPacket(); packet.setId(1L); packet.setPacketType(RedPacketType.NORMAL_GROUP);
        packet.setStatus(RedPacketStatus.REFUNDED); packet.setPacketCount(3); packet.setRemainingCount(0);
        when(packets.findAllById(List.of(1L))).thenReturn(List.of(packet));
        when(claims.countByPacketId(1L)).thenReturn(1L);
        var service = new WalletConversationCards(repo, packets, claims, groups, members, json,
            mock(com.chat99.server.im.ImUserIdService.class));
        Map<?, ?> card = (Map<?, ?>) ((List<?>) service.list(auth("u1"), "u2", false, null, "").get("cards")).get(0);
        assertThat(card.get("status")).isEqualTo("refunded");
        assertThat(((Number) card.get("claimedCount")).longValue()).isEqualTo(1L);
        assertThat(card.get("cardDeliveryState")).isEqualTo("PENDING");
        verifyNoInteractions(im);
    }
}
