package com.chat99.server.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class MeFriendsChangesServiceTest {

    @Mock
    FriendContactChangeRepository changeRepository;
    @Mock
    UserFriendRepository friendRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    UserPrivacyService privacyService;

    ObjectMapper json = new ObjectMapper();

    MeFriendsChangesService service;

    @BeforeEach
    void setUp() {
        service = new MeFriendsChangesService(changeRepository, friendRepository, userRepository, privacyService, json);
    }

    @Test
    void snapshot_usesCurrentRelationsForItemsAndTotalWithStableKeysetCursor() {
        UserFriend first = currentFriend("a", "b", 3L);
        UserFriend second = currentFriend("a", "c", 4L);
        when(changeRepository.findMaxRevisionForAccount("a")).thenReturn(9L);
        when(friendRepository.countCurrentSnapshotFriends("a")).thenReturn(2L);
        when(friendRepository.findCurrentSnapshotFriends(eq("a"), eq(""), any(Pageable.class)))
            .thenReturn(List.of(first, second));
        when(userRepository.findByUserIdIn(any())).thenReturn(List.of());
        when(friendRepository.findMutualFriendUserIdsAmong(eq("a"), any())).thenAnswer(inv -> ((java.util.Collection<String>) inv.getArgument(1)).contains("b") ? List.of("b") : List.of());

        MeFriendsChangesService.SnapshotResponse resp = service.snapshot("a", null, 1, null);

        assertThat(resp.snapshotRevision()).isEqualTo(9L);
        assertThat(resp.total()).isEqualTo(2L);
        assertThat(resp.items()).hasSize(1);
        assertThat(resp.items().get(0).id()).isEqualTo("b");
        assertThat(resp.items().get(0).operation()).isEqualTo("upsert");
        assertThat(resp.items().get(0).inMyFriendList()).isTrue();
        assertThat(resp.hasMore()).isTrue();
        assertThat(resp.opaqueCursor()).isNotBlank();

        when(friendRepository.findCurrentSnapshotFriends(eq("a"), eq("b"), any(Pageable.class)))
            .thenReturn(List.of(second));
        MeFriendsChangesService.SnapshotResponse terminal = service.snapshot(
            "a", resp.opaqueCursor(), 1, resp.snapshotRevision());
        assertThat(terminal.total()).isEqualTo(2L);
        assertThat(terminal.items()).hasSize(1);
        assertThat(terminal.items().get(0).id()).isEqualTo("c");
        assertThat(terminal.hasMore()).isFalse();
        assertThat(terminal.opaqueCursor()).isEmpty();
    }

    private static UserFriend currentFriend(String owner, String peer, long version) {
        UserFriend row = new UserFriend();
        row.setUserId(owner);
        row.setFriendUserId(peer);
        row.setStatus(UserFriend.STATUS_ACTIVE);
        row.setDeleted(false);
        row.setItemVersion(version);
        row.setUpdatedAt(java.time.Instant.parse("2026-09-02T00:00:00Z"));
        return row;
    }

    @Test
    void listChanges_returnsEventsAndNextSeq() {
        FriendContactChange row = new FriendContactChange();
        row.setSeq(10L);
        row.setAccountId("a");
        row.setEventType(FriendContactChangeWriter.TYPE_CONTACT_REMARK_UPDATED);
        row.setPeerUserId("b");
        row.setPayloadJson("{\"remark\":\"备注\",\"tcpAction\":\"remark_updated\"}");
        when(changeRepository.findMinSeqForAccount("a")).thenReturn(1L);
        when(changeRepository.findForAccountSinceSeq(eq("a"), eq(5L), any(Pageable.class)))
            .thenReturn(List.of(row));
        when(friendRepository.countByUserIdAndStatus("a", UserFriend.STATUS_ACTIVE)).thenReturn(3L);

        MeFriendsChangesService.FriendsChangesResponse resp = service.listChangesBySeq("a", 5L, 100);

        assertThat(resp.nextSeq()).isEqualTo(10L);
        assertThat(resp.hasMore()).isFalse();
        assertThat(resp.total()).isEqualTo(3L);
        assertThat(resp.events()).hasSize(1);
        assertThat(resp.events().get(0).type()).isEqualTo(FriendContactChangeWriter.TYPE_CONTACT_REMARK_UPDATED);
        assertThat(resp.events().get(0).remark()).isEqualTo("备注");
        assertThat(resp.events().get(0).tcpAction()).isEqualTo("remark_updated");
    }

    @Test
    void listChanges_expiredCursor_throwsSnapshotRequired() {
        when(changeRepository.findMinSeqForAccount("a")).thenReturn(100L);

        assertThatThrownBy(() -> service.listChangesBySeq("a", 50L, 100))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> {
                ResponseStatusException rse = (ResponseStatusException) ex;
                assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.GONE);
                assertThat(rse.getReason()).isEqualTo("SNAPSHOT_REQUIRED");
            });
    }

    @Test
    void listChanges_empty_usesMaxSeq() {
        when(changeRepository.findMinSeqForAccount("a")).thenReturn(null);
        when(changeRepository.findForAccountSinceSeq(eq("a"), eq(0L), any(Pageable.class)))
            .thenReturn(List.of());
        when(changeRepository.findMaxSeqForAccount("a")).thenReturn(7L);
        when(friendRepository.countByUserIdAndStatus("a", UserFriend.STATUS_ACTIVE)).thenReturn(0L);

        MeFriendsChangesService.FriendsChangesResponse resp = service.listChangesBySeq("a", 0L, 100);

        assertThat(resp.events()).isEmpty();
        assertThat(resp.nextSeq()).isEqualTo(7L);
    }

    @Test
    void changes_keepsCursorAtLastDeliveredRevisionUntilAllEventsAreReturned() {
        FriendContactChange first = change(10L, 10L, "first");
        FriendContactChange second = change(11L, 11L, "second");
        FriendContactChange third = change(12L, 12L, "third");
        when(changeRepository.findMinRevisionForAccount("a")).thenReturn(1L);
        when(changeRepository.findForAccountSinceRevision(eq("a"), eq(0L), any(Pageable.class)))
            .thenReturn(List.of(first, second, third));
        when(changeRepository.findMaxRevisionForAccount("a")).thenReturn(12L);

        MeFriendsChangesService.ChangesResponse firstPage = service.changes("a", null, 2);

        assertThat(firstPage.events()).extracting(MeFriendsChangesService.FriendItem::id)
            .containsExactly("first", "second");
        assertThat(firstPage.hasMore()).isTrue();
        assertThat(firstPage.toRevision()).isEqualTo(11L);

        when(changeRepository.findForAccountSinceRevision(eq("a"), eq(11L), any(Pageable.class)))
            .thenReturn(List.of(third));
        MeFriendsChangesService.ChangesResponse secondPage = service.changes("a", firstPage.opaqueCursor(), 2);

        assertThat(secondPage.events()).extracting(MeFriendsChangesService.FriendItem::id)
            .containsExactly("third");
        assertThat(secondPage.hasMore()).isFalse();
        assertThat(secondPage.toRevision()).isEqualTo(12L);
    }

    private static FriendContactChange change(long seq, long revision, String peerUserId) {
        FriendContactChange row = new FriendContactChange();
        row.setSeq(seq);
        row.setRevision(revision);
        row.setAccountId("a");
        row.setEventId("evt_" + peerUserId);
        row.setItemVersion(revision);
        row.setPeerUserId(peerUserId);
        row.setEventType(FriendContactChangeWriter.TYPE_CONTACT_UPDATED);
        row.setCreatedAt(1_788_330_000_000L + revision);
        return row;
    }

    @Test
    void changes_emptyPageNeverAcknowledgesAnUndeliveredConcurrentCommit() {
        when(changeRepository.findForAccountSinceRevision(eq("a"), eq(10L), any(Pageable.class)))
            .thenReturn(List.of());
        when(changeRepository.findMaxRevisionForAccount("a")).thenReturn(11L);

        var response = service.changes("a", com.chat99.server.sync.OpaqueCursor.encode("contacts", 10L, 10L), 100);

        assertThat(response.events()).isEmpty();
        assertThat(response.toRevision()).isEqualTo(10L);
        assertThat(com.chat99.server.sync.OpaqueCursor.decode(response.opaqueCursor()).revision()).isEqualTo(10L);
    }

    @Test
    void changes_wrongDomainCursorReturnsRecoverableGone() {
        var cursor = com.chat99.server.sync.OpaqueCursor.encode("groups", 10L, 10L);
        assertThatThrownBy(() -> service.changes("a", cursor, 100))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.GONE));
    }
}
