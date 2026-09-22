package com.chat99.server.messagearchive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.group.GroupMemberRepository;
import com.chat99.server.messagearchive.ImSnapshotModels.SnapshotResponse;
import com.chat99.server.messagearchive.ImSnapshotQuery.Candidate;
import com.chat99.server.messagearchive.MessageHistoryService.HistoryItem;
import com.chat99.server.messagearchive.MessageHistoryService.HistoryPage;
import com.chat99.server.push.ConversationPinService;
import com.chat99.server.im.ImUserIdService;
import com.chat99.server.push.ConversationPinService.PinItemView;
import com.chat99.server.push.ConversationPinService.PinListResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ImSnapshotServiceTest {

    @Mock ChatMessageTableRouter tableRouter;
    @Mock ImSnapshotQuery query;
    @Mock ConversationRecentRepository recentRepository;
    @Mock MessageHistoryService historyService;
    @Mock GroupMemberRepository memberRepository;
    @Mock ConversationPinService pinService;
    @Mock ImUserIdService imUserIdService;

    MessageArchiveProperties props;
    ImSnapshotService service;

    @BeforeEach
    void setUp() {
        props = propsWithMode("hybrid");
        service = new ImSnapshotService(
            props, tableRouter, query, recentRepository, historyService, memberRepository, pinService, imUserIdService);
        lenient().when(imUserIdService.toImAccount(org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(imUserIdService.toBusinessForDisplay(org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(tableRouter.physicalTablesBetween(anyLong(), anyLong()))
            .thenReturn(List.of("chat_message_202608"));
        lenient().when(pinService.list("u1"))
            .thenReturn(new PinListResponse(List.of(), 0L, 0L));
    }

    @Test
    void build_ordersMessagesAscending_andOmitsUnread() {
        when(recentRepository.listC2cCandidates(eq("u1"), anyLong(), anyInt()))
            .thenReturn(List.of(new Candidate("c2c", "peer1", 2_000L, null)));
        when(recentRepository.listGroupCandidates(eq("u1"), anyLong(), anyInt()))
            .thenReturn(List.of(new Candidate("group", "@TGS#g1", 3_000L, 9L)));

        when(historyService.listGroup(eq("u1"), eq("@TGS#g1"), isNull(), isNull(), isNull(), eq(40)))
            .thenReturn(new HistoryPage(List.of(
                item("g-new", "a", null, "@TGS#g1", 9L, 3_000L),
                item("g-old", "b", null, "@TGS#g1", 8L, 2_500L)), null, false));
        when(historyService.listC2c(eq("u1"), eq("peer1"), isNull(), isNull(), isNull(), eq(40)))
            .thenReturn(new HistoryPage(List.of(
                item("c-new", "peer1", "u1", null, null, 2_000L),
                item("c-old", "u1", "peer1", null, null, 1_500L)), null, false));

        SnapshotResponse resp = service.build("u1", 20, 40);

        assertThat(resp.degraded()).isFalse();
        assertThat(resp.conversations()).hasSize(2);
        assertThat(resp.conversations().get(0).conversationId()).isEqualTo("group_@TGS#g1");
        assertThat(resp.conversations().get(0).pinned()).isFalse();
        assertThat(resp.preload().get(0).messages()).extracting(m -> m.msgKey())
            .containsExactly("g-old", "g-new");
        assertThat(resp.preload().get(0).messages()).extracting(m -> m.msgId())
            .containsOnlyNulls();
        assertThat(resp.conversations().get(0).getClass().getRecordComponents())
            .extracting(c -> c.getName())
            .contains("pinned", "pinnedAt")
            .doesNotContain("unread");
        verify(query, never()).listC2cCandidates(anyList(), eq("u1"), anyLong(), anyInt());
    }

    @Test
    void build_splitMode_c2cThenGroup_sections() {
        when(recentRepository.listC2cCandidates(eq("u1"), anyLong(), eq(40)))
            .thenReturn(List.of(
                new Candidate("c2c", "p2", 5_000L, null),
                new Candidate("c2c", "p1", 4_000L, null)));
        when(recentRepository.listGroupCandidates(eq("u1"), anyLong(), eq(40)))
            .thenReturn(List.of(
                new Candidate("group", "@TGS#g2", 9_000L, 2L),
                new Candidate("group", "@TGS#g1", 8_000L, 1L)));

        when(historyService.listC2c(eq("u1"), eq("p2"), isNull(), isNull(), isNull(), eq(40)))
            .thenReturn(new HistoryPage(List.of(item("c2", "p2", "u1", null, null, 5_000L)), null, false));
        when(historyService.listC2c(eq("u1"), eq("p1"), isNull(), isNull(), isNull(), eq(40)))
            .thenReturn(new HistoryPage(List.of(item("c1", "p1", "u1", null, null, 4_000L)), null, false));
        when(historyService.listGroup(eq("u1"), eq("@TGS#g2"), isNull(), isNull(), isNull(), eq(40)))
            .thenReturn(new HistoryPage(List.of(item("g2", "a", null, "@TGS#g2", 2L, 9_000L)), null, false));
        when(historyService.listGroup(eq("u1"), eq("@TGS#g1"), isNull(), isNull(), isNull(), eq(40)))
            .thenReturn(new HistoryPage(List.of(item("g1", "b", null, "@TGS#g1", 1L, 8_000L)), null, false));

        SnapshotResponse resp = service.build("u1", null, 40, 40, 40);

        assertThat(resp.conversations()).extracting(c -> c.conversationId())
            .containsExactly("c2c_p2", "c2c_p1", "group_@TGS#g2", "group_@TGS#g1");
        assertThat(resp.conversations()).extracting(c -> c.chatType())
            .containsExactly("c2c", "c2c", "group", "group");
    }

    @Test
    void build_splitMode_pinsFirstWithinSide_evenIfNotInRecent() {
        when(pinService.list("u1")).thenReturn(new PinListResponse(List.of(
            new PinItemView("c2c", "pinnedPeer", 9_000L, 9_000L)), 0L, 0L));
        when(recentRepository.listC2cCandidates(eq("u1"), anyLong(), eq(40)))
            .thenReturn(List.of(new Candidate("c2c", "hot", 8_000L, null)));
        when(recentRepository.listGroupCandidates(eq("u1"), anyLong(), eq(40)))
            .thenReturn(List.of());

        when(historyService.listC2c(eq("u1"), eq("pinnedPeer"), isNull(), isNull(), isNull(), eq(40)))
            .thenReturn(new HistoryPage(List.of(item("pin", "pinnedPeer", "u1", null, null, 100L)), null, false));
        when(historyService.listC2c(eq("u1"), eq("hot"), isNull(), isNull(), isNull(), eq(40)))
            .thenReturn(new HistoryPage(List.of(item("hot", "hot", "u1", null, null, 8_000L)), null, false));

        SnapshotResponse resp = service.build("u1", null, 40, 40, 40);

        assertThat(resp.conversations()).extracting(c -> c.conversationId())
            .containsExactly("c2c_pinnedPeer", "c2c_hot");
        assertThat(resp.conversations().get(0).pinned()).isTrue();
        assertThat(resp.conversations().get(0).pinnedAt()).isEqualTo(9_000L);
        assertThat(resp.conversations().get(1).pinned()).isFalse();
        assertThat(resp.conversations().get(1).pinnedAt()).isNull();
    }

    @Test
    void build_pinnedWithoutHistory_stillEmitted() {
        when(pinService.list("u1")).thenReturn(new PinListResponse(List.of(
            new PinItemView("group", "@TGS#pin", 5_000L, 5_000L)), 0L, 0L));
        when(recentRepository.listC2cCandidates(eq("u1"), anyLong(), eq(40))).thenReturn(List.of());
        when(recentRepository.listGroupCandidates(eq("u1"), anyLong(), eq(40))).thenReturn(List.of());
        when(historyService.listGroup(eq("u1"), eq("@TGS#pin"), isNull(), isNull(), isNull(), eq(40)))
            .thenReturn(new HistoryPage(List.of(), null, false));

        SnapshotResponse resp = service.build("u1", null, 40, 40, 40);

        assertThat(resp.conversations()).hasSize(1);
        assertThat(resp.conversations().get(0).conversationId()).isEqualTo("group_@TGS#pin");
        assertThat(resp.conversations().get(0).pinned()).isTrue();
        assertThat(resp.conversations().get(0).lastMessage()).isNull();
        assertThat(resp.preload().get(0).messages()).isEmpty();
    }

    @Test
    void build_unpinnedWithoutHistory_skipped() {
        when(recentRepository.listC2cCandidates(eq("u1"), anyLong(), eq(40)))
            .thenReturn(List.of(new Candidate("c2c", "ghost", 8_000L, null)));
        when(recentRepository.listGroupCandidates(eq("u1"), anyLong(), eq(40))).thenReturn(List.of());
        when(historyService.listC2c(eq("u1"), eq("ghost"), isNull(), isNull(), isNull(), eq(40)))
            .thenReturn(new HistoryPage(List.of(), null, false));

        SnapshotResponse resp = service.build("u1", null, 40, 40, 40);
        assertThat(resp.conversations()).isEmpty();
    }

    @Test
    void build_hybridFallsBackToLegacyWhenRecentEmpty() {
        when(recentRepository.listC2cCandidates(eq("u1"), anyLong(), anyInt())).thenReturn(List.of());
        when(recentRepository.listGroupCandidates(eq("u1"), anyLong(), anyInt())).thenReturn(List.of());
        when(query.listC2cCandidates(anyList(), eq("u1"), anyLong(), anyInt()))
            .thenReturn(List.of(new Candidate("c2c", "keep", 8_000L, null)));
        when(memberRepository.findActiveGroupIdsByUserId("u1")).thenReturn(List.of());
        when(query.listGroupCandidates(anyList(), anyList(), anyLong(), anyInt())).thenReturn(List.of());
        when(historyService.listC2c(eq("u1"), eq("keep"), isNull(), isNull(), isNull(), anyInt()))
            .thenReturn(new HistoryPage(List.of(item("k1", "keep", "u1", null, null, 8_000L)), null, false));

        SnapshotResponse resp = service.build("u1", null, null);
        assertThat(resp.conversations()).hasSize(1);
        assertThat(resp.conversations().get(0).peerId()).isEqualTo("keep");
        verify(query).listC2cCandidates(anyList(), eq("u1"), anyLong(), anyInt());
    }

    @Test
    void build_recentMode_doesNotFallback() {
        props = propsWithMode("recent");
        service = new ImSnapshotService(
            props, tableRouter, query, recentRepository, historyService, memberRepository, pinService, imUserIdService);
        when(recentRepository.listC2cCandidates(eq("u1"), anyLong(), anyInt())).thenReturn(List.of());
        when(recentRepository.listGroupCandidates(eq("u1"), anyLong(), anyInt())).thenReturn(List.of());

        SnapshotResponse resp = service.build("u1", 20, 40);
        assertThat(resp.conversations()).isEmpty();
        verify(query, never()).listC2cCandidates(anyList(), eq("u1"), anyLong(), anyInt());
    }

    @Test
    void build_blankUser_returnsDegradedEmpty() {
        SnapshotResponse resp = service.build(" ", 20, 40);
        assertThat(resp.degraded()).isTrue();
        assertThat(resp.conversations()).isEmpty();
    }

    @Test
    void clamp_andConversationIdHelpers() {
        assertThat(ImSnapshotService.clamp(null, 20, 1, 50)).isEqualTo(20);
        assertThat(ImSnapshotService.toConversationId("group", "@TGS#x")).isEqualTo("group_@TGS#x");
    }

    @Test
    void mergeSide_pinsBeforeRecent_andDedups() {
        List<PinItemView> pins = List.of(
            new PinItemView("c2c", "a", 100L, 100L),
            new PinItemView("group", "g", 200L, 200L));
        List<Candidate> recent = List.of(
            new Candidate("c2c", "a", 999L, null),
            new Candidate("c2c", "b", 50L, null));
        var merged = ImSnapshotService.mergeSide(pins, "c2c", recent, 40);
        assertThat(merged).extracting(r -> r.candidate().peerId()).containsExactly("a", "b");
        assertThat(merged.get(0).pinned()).isTrue();
        assertThat(merged.get(1).pinned()).isFalse();
    }

    private static MessageArchiveProperties propsWithMode(String mode) {
        return new MessageArchiveProperties(
            true, 500, 10, null, null, null, null, 60_000L,
            new MessageArchiveProperties.Snapshot(7, 20, 50, 40, 40, 50, 50, 40, 30, 50, 5_000L, 2, 3, mode),
            null);
    }

    private static HistoryItem item(String key, String from, String peer, String groupId, Long seq, long ms) {
        return new HistoryItem(key, null, from, peer, groupId, seq, ms, "TIMTextElem", "hi",
            List.of(Map.of("MsgType", "TIMTextElem")), 1);
    }
}
