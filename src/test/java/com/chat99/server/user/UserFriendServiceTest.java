package com.chat99.server.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.realtime.FriendListRealtimePublisher;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class UserFriendServiceTest {

    @Mock
    UserFriendRepository friendRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    UserFriendMutualCache mutualCache;
    @Mock
    FriendListRealtimePublisher friendListRealtimePublisher;
    @Mock
    UserPrivacyService privacyService;
    @Mock
    MeFriendsChangesService friendsChangesService;
    @Mock
    FriendImSyncService friendImSyncService;

    @InjectMocks
    UserFriendService service;

    @BeforeEach
    void stubCurrentSyncSeq() {
        lenient().when(friendsChangesService.currentSyncSeq(any())).thenReturn(0L);
    }

    @Test
    void getFriendRelation_mutualFriend() {
        User peer = activeUser("b");
        when(userRepository.findByUserId("b")).thenReturn(Optional.of(peer));
        when(mutualCache.getRelationEdges("a", "b")).thenReturn(Optional.empty());
        when(friendRepository.findActiveEdgesBetween("a", "b")).thenReturn(List.of(activeEdge("a", "b"), activeEdge("b", "a")));

        UserFriendService.FriendRelationResponse r = service.getFriendRelation("a", "b");

        assertThat(r.isFriend()).isTrue();
        assertThat(r.inMyFriendList()).isTrue();
        assertThat(r.peerDeletedMe()).isFalse();
        assertThat(r.canMessage()).isTrue();
        verify(mutualCache).putRelationEdges("a", "b", true, true);
    }

    @Test
    void getFriendRelation_peerDeletedMe() {
        User peer = activeUser("b");
        when(userRepository.findByUserId("b")).thenReturn(Optional.of(peer));
        when(mutualCache.getRelationEdges("a", "b")).thenReturn(Optional.empty());
        when(friendRepository.findActiveEdgesBetween("a", "b")).thenReturn(List.of(activeEdge("a", "b")));

        UserFriendService.FriendRelationResponse r = service.getFriendRelation("a", "b");

        assertThat(r.isFriend()).isFalse();
        assertThat(r.inMyFriendList()).isTrue();
        assertThat(r.peerDeletedMe()).isTrue();
        assertThat(r.canMessage()).isFalse();
    }

    @Test
    void getFriendRelation_notFriend() {
        User peer = activeUser("b");
        when(userRepository.findByUserId("b")).thenReturn(Optional.of(peer));
        when(mutualCache.getRelationEdges("a", "b")).thenReturn(Optional.empty());
        when(friendRepository.findActiveEdgesBetween("a", "b")).thenReturn(List.of());

        UserFriendService.FriendRelationResponse r = service.getFriendRelation("a", "b");

        assertThat(r.isFriend()).isFalse();
        assertThat(r.inMyFriendList()).isFalse();
        assertThat(r.peerDeletedMe()).isFalse();
        assertThat(r.canMessage()).isFalse();
    }

    @Test
    void getFriendRelation_cacheHit_skipsDb() {
        when(mutualCache.getRelationEdges("a", "b"))
            .thenReturn(Optional.of(new UserFriendMutualCache.RelationEdges(true, true)));

        UserFriendService.FriendRelationResponse r = service.getFriendRelation("a", "b");

        assertThat(r.isFriend()).isTrue();
        verify(userRepository, never()).findByUserId(any());
        verify(friendRepository, never()).findActiveEdgesBetween(any(), any());
    }

    @Test
    void isMutualActive_usesCacheWhenPresent() {
        when(mutualCache.getMutual("a", "b")).thenReturn(true);
        assertThat(service.isMutualActive("a", "b")).isTrue();
        verify(friendRepository, never()).isMutualActive(any(), any());
    }

    @Test
    void isMutualActive_loadsFromDbAndCaches() {
        when(mutualCache.getMutual("a", "b")).thenReturn(null);
        when(friendRepository.isMutualActive("a", "b")).thenReturn(true);
        assertThat(service.isMutualActive("a", "b")).isTrue();
        verify(mutualCache).putMutual("a", "b", true);
    }

    @Test
    void deleteFriendMutual_softDeletesBothEdgesAndPublishesMutualDeleted() {
        UserFriend ab = activeEdge("a", "b");
        ab.setItemVersion(2L);
        UserFriend ba = activeEdge("b", "a");
        ba.setItemVersion(4L);
        when(friendRepository.findByUserIdAndFriendUserId("a", "b")).thenReturn(Optional.of(ab));
        when(friendRepository.findByUserIdAndFriendUserId("b", "a")).thenReturn(Optional.of(ba));

        UserFriendService.DeleteFriendResponse resp = service.deleteFriendMutual("a", "b");

        assertThat(resp.deleted()).isTrue();
        assertThat(ab.getStatus()).isEqualTo(UserFriend.STATUS_REMOVED);
        assertThat(ab.isDeleted()).isTrue();
        assertThat(ab.getDeletedAt()).isNotNull();
        assertThat(ab.getItemVersion()).isEqualTo(3L);
        assertThat(ba.getStatus()).isEqualTo(UserFriend.STATUS_REMOVED);
        assertThat(ba.isDeleted()).isTrue();
        assertThat(ba.getDeletedAt()).isNotNull();
        assertThat(ba.getItemVersion()).isEqualTo(5L);
        verify(mutualCache).evict("a", "b");
        verify(friendListRealtimePublisher).mutualDeleted("a", "b");
        verify(friendListRealtimePublisher, never()).oneWayDeleted(any(), any());
        verify(friendImSyncService).syncDeleteBoth("a", "b");
    }

    @Test
    void listFriends_marksPeerDeletedMeWhenReverseEdgeMissing_legacyOneSidedData() {
        UserFriend row = friendRow(10L, "a", "b", "Bob", null);
        when(friendRepository.findByUserIdAndStatusAndIdGreaterThanOrderByIdAsc(
            eq("a"), eq(UserFriend.STATUS_ACTIVE), eq(0L), any(Pageable.class)))
            .thenReturn(List.of(row));
        when(friendRepository.findMutualFriendUserIdsAmong(eq("a"), eq(List.of("b"))))
            .thenReturn(List.of());
        when(friendRepository.countByUserIdAndStatus("a", UserFriend.STATUS_ACTIVE)).thenReturn(1L);
        when(userRepository.findOnlinePresenceByUserIds(List.of("b")))
            .thenReturn(List.of());

        UserFriendService.FriendListResponse list = service.listFriends("a");

        assertThat(list.items()).hasSize(1);
        assertThat(list.hasMore()).isFalse();
        assertThat(list.nextCursor()).isNull();
        assertThat(list.total()).isEqualTo(1L);
        UserFriendService.FriendItem item = list.items().get(0);
        assertThat(item.peerDeletedMe()).isTrue();
        assertThat(item.canMessage()).isFalse();
        assertThat(item.inMyFriendList()).isTrue();
        assertThat(item.isFriend()).isFalse();
        assertThat(item.remark()).isEmpty();
        assertThat(item.lastActiveAt()).isNull();
        verify(friendRepository, never()).existsByUserIdAndFriendUserIdAndStatus(any(), any(), any(Integer.class));
    }

    @Test
    void listFriends_includesRelationFieldsAndLastActiveAt() {
        UserFriend row = friendRow(11L, "a", "b", "Alice", "爱丽丝");
        when(friendRepository.findByUserIdAndStatusAndIdGreaterThanOrderByIdAsc(
            eq("a"), eq(UserFriend.STATUS_ACTIVE), eq(0L), any(Pageable.class)))
            .thenReturn(List.of(row));
        when(friendRepository.findMutualFriendUserIdsAmong(eq("a"), eq(List.of("b"))))
            .thenReturn(List.of("b"));
        when(friendRepository.countByUserIdAndStatus("a", UserFriend.STATUS_ACTIVE)).thenReturn(1L);
        when(userRepository.findOnlinePresenceByUserIds(List.of("b")))
            .thenReturn(List.of(onlinePresence("b", java.time.Instant.ofEpochMilli(1718452800000L), LastActiveVisibility.everyone)));
        when(privacyService.lastActiveAtEpochMillis(org.mockito.ArgumentMatchers.any(UserRepository.OnlinePresenceView.class)))
            .thenReturn(1718452800000L);

        UserFriendService.FriendListResponse list = service.listFriends("a");

        UserFriendService.FriendItem item = list.items().get(0);
        assertThat(item.peerDeletedMe()).isFalse();
        assertThat(item.canMessage()).isTrue();
        assertThat(item.inMyFriendList()).isTrue();
        assertThat(item.isFriend()).isTrue();
        assertThat(item.remark()).isEqualTo("爱丽丝");
        assertThat(item.lastActiveAt()).isEqualTo(1718452800000L);
        assertThat(item.lastActiveVisibility()).isEqualTo(LastActiveVisibility.everyone);
    }

    @Test
    void updateRemark_publishesRealtimeEvent() {
        UserFriend row = new UserFriend();
        row.setUserId("a");
        row.setFriendUserId("b");
        row.setStatus(UserFriend.STATUS_ACTIVE);
        when(friendRepository.findByUserIdAndFriendUserId("a", "b")).thenReturn(Optional.of(row));

        UserFriendService.RemarkUpdateResponse resp = service.updateRemark("a", "b", "新备注");

        assertThat(resp.friendUserId()).isEqualTo("b");
        assertThat(resp.remark()).isEqualTo("新备注");
        assertThat(row.getRemark()).isEqualTo("新备注");
        verify(friendRepository).save(row);
        verify(friendListRealtimePublisher).remarkUpdated("a", "b");
        verify(friendImSyncService).syncRemark("a", "b", "新备注");
    }

    @Test
    void updateRemark_trimsWhitespaceAndAllowsClearing() {
        UserFriend row = activeEdge("a", "b");
        when(friendRepository.findByUserIdAndFriendUserId("a", "b")).thenReturn(Optional.of(row));

        UserFriendService.RemarkUpdateResponse trimmed = service.updateRemark("a", "b", "  宝宝儿123  ");
        UserFriendService.RemarkUpdateResponse cleared = service.updateRemark("a", "b", "   ");

        assertThat(trimmed.remark()).isEqualTo("宝宝儿123");
        assertThat(cleared.remark()).isNull();
        assertThat(row.getRemark()).isNull();
        verify(friendRepository, org.mockito.Mockito.times(2)).save(row);
        verify(friendImSyncService).syncRemark("a", "b", "宝宝儿123");
        verify(friendImSyncService).syncRemark("a", "b", "");
    }

    @Test
    void updateRemark_rejectsTooLongRemark() {
        UserFriend row = activeEdge("a", "b");
        when(friendRepository.findByUserIdAndFriendUserId("a", "b")).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.updateRemark("a", "b", "x".repeat(101)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("INVALID_INPUT");
        verify(friendRepository, never()).save(any(UserFriend.class));
        verify(friendListRealtimePublisher, never()).remarkUpdated(any(), any());
    }

    @Test
    void updateRemark_rejectsInactiveFriend() {
        UserFriend row = activeEdge("a", "b");
        row.setStatus(UserFriend.STATUS_REMOVED);
        when(friendRepository.findByUserIdAndFriendUserId("a", "b")).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.updateRemark("a", "b", "备注"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("FRIEND_NOT_FOUND");
        verify(friendRepository, never()).save(any(UserFriend.class));
    }

    @Test
    void forceDeleteMutual_removesBothEdgesWithFullTombstone() {
        UserFriend ab = activeEdge("a", "b");
        ab.setItemVersion(1L);
        UserFriend ba = activeEdge("b", "a");
        ba.setItemVersion(1L);
        when(friendRepository.findByUserIdAndFriendUserId("a", "b")).thenReturn(Optional.of(ab));
        when(friendRepository.findByUserIdAndFriendUserId("b", "a")).thenReturn(Optional.of(ba));

        service.forceDeleteMutual("a", "b");

        assertThat(ab.getStatus()).isEqualTo(UserFriend.STATUS_REMOVED);
        assertThat(ab.isDeleted()).isTrue();
        assertThat(ab.getDeletedAt()).isNotNull();
        assertThat(ab.getItemVersion()).isEqualTo(2L);
        assertThat(ba.getStatus()).isEqualTo(UserFriend.STATUS_REMOVED);
        assertThat(ba.isDeleted()).isTrue();
        assertThat(ba.getDeletedAt()).isNotNull();
        assertThat(ba.getItemVersion()).isEqualTo(2L);
        verify(friendRepository).save(ab);
        verify(friendRepository).save(ba);
        verify(mutualCache).evict("a", "b");
        verify(friendListRealtimePublisher).mutualDeleted("a", "b");
        verify(friendImSyncService).syncDeleteBoth("a", "b");
    }

    @Test
    void bindMutualFriends_revivesTombstoneEdgeInsteadOfInsert() {
        User a = activeUser("a");
        a.setNickname("Alice");
        User b = activeUser("b");
        b.setNickname("Bob");
        when(userRepository.findByUserId("a")).thenReturn(Optional.of(a));
        when(userRepository.findByUserId("b")).thenReturn(Optional.of(b));

        UserFriend tombstone = tombstoneEdge("a", "b", 5L);
        when(friendRepository.findIncludingDeletedByUserIdAndFriendUserId("a", "b"))
            .thenReturn(Optional.of(tombstone));
        when(friendRepository.findIncludingDeletedByUserIdAndFriendUserId("b", "a"))
            .thenReturn(Optional.empty());

        service.bindMutualFriends("a", "b");

        assertThat(tombstone.isDeleted()).isFalse();
        assertThat(tombstone.getDeletedAt()).isNull();
        assertThat(tombstone.getStatus()).isEqualTo(UserFriend.STATUS_ACTIVE);
        assertThat(tombstone.getItemVersion()).isEqualTo(6L);
        verify(friendRepository).findIncludingDeletedByUserIdAndFriendUserId("a", "b");
        verify(friendRepository).save(tombstone);
        verify(friendRepository, never()).findByUserIdAndFriendUserId(eq("a"), eq("b"));
        verify(friendImSyncService).syncAddBoth("a", "b");
    }

    @Test
    void bindMutualFriends_syncToImFalse_skipsImSync() {
        User a = activeUser("a");
        a.setNickname("Alice");
        User b = activeUser("b");
        b.setNickname("Bob");
        when(userRepository.findByUserId("a")).thenReturn(Optional.of(a));
        when(userRepository.findByUserId("b")).thenReturn(Optional.of(b));
        when(friendRepository.findIncludingDeletedByUserIdAndFriendUserId("a", "b"))
            .thenReturn(Optional.empty());
        when(friendRepository.findIncludingDeletedByUserIdAndFriendUserId("b", "a"))
            .thenReturn(Optional.empty());

        service.bindMutualFriends("a", "b", false);

        verify(friendImSyncService, never()).syncAddBoth(any(), any());
    }

    @Test
    void reviveActiveEdge_revivesTombstone() {
        User peer = activeUser("b");
        peer.setNickname("Bob");
        when(userRepository.findByUserId("b")).thenReturn(Optional.of(peer));
        UserFriend tombstone = tombstoneEdge("a", "b", 3L);
        when(friendRepository.findIncludingDeletedByUserIdAndFriendUserId("a", "b"))
            .thenReturn(Optional.of(tombstone));

        service.reviveActiveEdge("a", "b");

        assertThat(tombstone.isDeleted()).isFalse();
        assertThat(tombstone.getDeletedAt()).isNull();
        assertThat(tombstone.getStatus()).isEqualTo(UserFriend.STATUS_ACTIVE);
        assertThat(tombstone.getItemVersion()).isEqualTo(4L);
        verify(friendRepository).save(tombstone);
        verify(mutualCache).evict("a", "b");
    }

    @Test
    void bindMutualFriends_createsWhenNoRow() {
        User a = activeUser("a");
        a.setNickname("Alice");
        User b = activeUser("b");
        b.setNickname("Bob");
        when(userRepository.findByUserId("a")).thenReturn(Optional.of(a));
        when(userRepository.findByUserId("b")).thenReturn(Optional.of(b));
        when(friendRepository.findIncludingDeletedByUserIdAndFriendUserId("a", "b"))
            .thenReturn(Optional.empty());
        when(friendRepository.findIncludingDeletedByUserIdAndFriendUserId("b", "a"))
            .thenReturn(Optional.empty());

        service.bindMutualFriends("a", "b");

        ArgumentCaptor<UserFriend> captor = ArgumentCaptor.forClass(UserFriend.class);
        verify(friendRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(row -> {
            assertThat(row.getId()).isNull();
            assertThat(row.getStatus()).isEqualTo(UserFriend.STATUS_ACTIVE);
            assertThat(row.isDeleted()).isFalse();
        });
        assertThat(captor.getAllValues())
            .extracting(UserFriend::getUserId, UserFriend::getFriendUserId)
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("a", "b"),
                org.assertj.core.groups.Tuple.tuple("b", "a"));
        verify(friendImSyncService).syncAddBoth("a", "b");
    }

    private static UserFriend activeEdge(String userId, String friendUserId) {
        UserFriend row = new UserFriend();
        row.setUserId(userId);
        row.setFriendUserId(friendUserId);
        row.setStatus(UserFriend.STATUS_ACTIVE);
        return row;
    }

    private static UserFriend tombstoneEdge(String userId, String friendUserId, long version) {
        UserFriend row = new UserFriend();
        row.setId(99L);
        row.setUserId(userId);
        row.setFriendUserId(friendUserId);
        row.setStatus(UserFriend.STATUS_REMOVED);
        row.setDeleted(true);
        row.setDeletedAt(Instant.parse("2026-09-08T09:20:01Z"));
        row.setItemVersion(version);
        return row;
    }

    private static User activeUser(String userId) {
        User u = new User();
        u.setUserId(userId);
        u.setStatus(1);
        return u;
    }

    @Test
    void listFriends_returnsRawLastActiveAtAndVisibilityWhenHidden() {
        UserFriend row = friendRow(12L, "a", "b", null, null);
        when(friendRepository.findByUserIdAndStatusAndIdGreaterThanOrderByIdAsc(
            eq("a"), eq(UserFriend.STATUS_ACTIVE), eq(0L), any(Pageable.class)))
            .thenReturn(List.of(row));
        when(friendRepository.findMutualFriendUserIdsAmong(eq("a"), eq(List.of("b"))))
            .thenReturn(List.of("b"));
        when(friendRepository.countByUserIdAndStatus("a", UserFriend.STATUS_ACTIVE)).thenReturn(1L);
        when(userRepository.findOnlinePresenceByUserIds(List.of("b")))
            .thenReturn(List.of(onlinePresence("b", java.time.Instant.ofEpochMilli(1718452800000L), LastActiveVisibility.hidden)));
        when(privacyService.lastActiveAtEpochMillis(org.mockito.ArgumentMatchers.any(UserRepository.OnlinePresenceView.class)))
            .thenReturn(1718452800000L);

        UserFriendService.FriendListResponse list = service.listFriends("a");

        assertThat(list.items().get(0).lastActiveAt()).isEqualTo(1718452800000L);
        assertThat(list.items().get(0).lastActiveVisibility()).isEqualTo(LastActiveVisibility.hidden);
    }

    @Test
    void listFriends_paginatesWithCursorAndHasMore() {
        UserFriend a = friendRow(1L, "u", "f1", null, null);
        UserFriend b = friendRow(2L, "u", "f2", null, null);
        UserFriend extra = friendRow(3L, "u", "f3", null, null);
        when(friendRepository.findByUserIdAndStatusAndIdGreaterThanOrderByIdAsc(
            eq("u"), eq(UserFriend.STATUS_ACTIVE), eq(0L), any(Pageable.class)))
            .thenReturn(List.of(a, b, extra));
        when(friendRepository.findMutualFriendUserIdsAmong(eq("u"), eq(List.of("f1", "f2"))))
            .thenReturn(List.of("f1", "f2"));
        when(friendRepository.countByUserIdAndStatus("u", UserFriend.STATUS_ACTIVE)).thenReturn(3L);
        when(userRepository.findOnlinePresenceByUserIds(List.of("f1", "f2")))
            .thenReturn(List.of());

        UserFriendService.FriendListResponse page1 = service.listFriends("u", 2, null);

        assertThat(page1.items()).extracting(UserFriendService.FriendItem::friendUserId)
            .containsExactly("f1", "f2");
        assertThat(page1.hasMore()).isTrue();
        assertThat(page1.nextCursor()).isEqualTo("2");
        assertThat(page1.total()).isEqualTo(3L);

        when(friendRepository.findByUserIdAndStatusAndIdGreaterThanOrderByIdAsc(
            eq("u"), eq(UserFriend.STATUS_ACTIVE), eq(2L), any(Pageable.class)))
            .thenReturn(List.of(extra));
        when(friendRepository.findMutualFriendUserIdsAmong(eq("u"), eq(List.of("f3"))))
            .thenReturn(List.of("f3"));
        when(userRepository.findOnlinePresenceByUserIds(List.of("f3")))
            .thenReturn(List.of());

        UserFriendService.FriendListResponse page2 = service.listFriends("u", 2, "2");

        assertThat(page2.items()).extracting(UserFriendService.FriendItem::friendUserId)
            .containsExactly("f3");
        assertThat(page2.hasMore()).isFalse();
        assertThat(page2.nextCursor()).isNull();
    }

    @Test
    void listFriends_rejectsInvalidLimitAndCursor() {
        assertThatThrownBy(() -> service.listFriends("u", 0, null))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("INVALID_INPUT");
        assertThatThrownBy(() -> service.listFriends("u", 201, null))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("INVALID_INPUT");
        assertThatThrownBy(() -> service.listFriends("u", 10, "abc"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("INVALID_INPUT");
    }

    private static UserFriend friendRow(long id, String userId, String friendUserId, String nickname, String remark) {
        UserFriend row = new UserFriend();
        row.setId(id);
        row.setUserId(userId);
        row.setFriendUserId(friendUserId);
        row.setStatus(UserFriend.STATUS_ACTIVE);
        row.setFriendNickname(nickname);
        row.setRemark(remark);
        return row;
    }

    private static UserRepository.OnlinePresenceView onlinePresence(
            String userId, java.time.Instant lastActiveAt, LastActiveVisibility visibility) {
        return new UserRepository.OnlinePresenceView() {
            @Override
            public String getUserId() {
                return userId;
            }

            @Override
            public java.time.Instant getLastActiveAt() {
                return lastActiveAt;
            }

            @Override
            public LastActiveVisibility getLastActiveVisibility() {
                return visibility;
            }
        };
    }
}
