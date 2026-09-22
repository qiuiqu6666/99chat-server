package com.chat99.server.group;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImAdminClient.GroupAdminInfo;
import com.chat99.server.im.ImAdminClient.ImRoleFetchResult;
import com.chat99.server.im.ImGroupFetchResult;
import com.chat99.server.im.restqueue.ImRestQueuePublisher;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class GroupProjectionHydrateNotMemberTest {

    @Mock GroupProfileRepository profileRepository;
    @Mock GroupMemberRepository memberRepository;
    @Mock GroupChangeEventRepository changeEventRepository;
    @Mock ImAdminClient im;
    @Mock GroupAvatarDefaults avatarDefaults;
    @Mock ObjectProvider<ImRestQueuePublisher> restQueue;
    @Mock ObjectProvider<UserOwnedGroupService> ownedGroupService;
    @Mock ObjectProvider<MeGroupsListCache> meGroupsListCache;

    GroupProjectionService projection;

    @BeforeEach
    void setUp() {
        projection = new GroupProjectionService(
            profileRepository, memberRepository, changeEventRepository, im, avatarDefaults,
            restQueue, ownedGroupService, meGroupsListCache);
        lenient().when(meGroupsListCache.getIfAvailable()).thenReturn(null);
        lenient().when(changeEventRepository.findRecentByGroupAndAction(any(), any(), any(Long.class), any()))
            .thenReturn(List.of());
    }

    @Test
    void hydrate_notMember_softDeletesLocalActiveRow() {
        GroupProfile profile = aliveProfile("g1");
        when(profileRepository.findById("g1")).thenReturn(Optional.of(profile));
        when(im.fetchGroupAdminInfoResult("g1")).thenReturn(ImGroupFetchResult.ok(adminInfo("g1")));
        when(im.getRoleInGroupResult("g1", "u1"))
            .thenReturn(new ImRoleFetchResult("NotMember", ImRoleFetchResult.Status.OK, 0));
        when(memberRepository.findByGroupIdAndUserIdActive("g1", "u1"))
            .thenReturn(Optional.of(activeMember("g1", "u1")));
        when(im.countGroupMembers("g1")).thenReturn(10);

        projection.hydrateGroupFromIm("g1", "u1");

        verify(memberRepository).bulkSoftDeleteByGroupIdAndUserId("g1", "u1");
        verify(im, never()).getMemberNameCard(any(), any());
    }

    @Test
    void hydrate_notMember_skipsWhenLocalAlreadyGone() {
        GroupProfile profile = aliveProfile("g1");
        when(profileRepository.findById("g1")).thenReturn(Optional.of(profile));
        when(im.fetchGroupAdminInfoResult("g1")).thenReturn(ImGroupFetchResult.ok(adminInfo("g1")));
        when(im.getRoleInGroupResult("g1", "u1"))
            .thenReturn(new ImRoleFetchResult("NotMember", ImRoleFetchResult.Status.OK, 0));
        when(memberRepository.findByGroupIdAndUserIdActive("g1", "u1")).thenReturn(Optional.empty());

        projection.hydrateGroupFromIm("g1", "u1");

        verify(memberRepository, never()).bulkSoftDeleteByGroupIdAndUserId(any(), any());
    }

    @Test
    void hydrate_member_upsertsLocalRow() {
        GroupProfile profile = aliveProfile("g1");
        when(profileRepository.findById("g1")).thenReturn(Optional.of(profile));
        when(im.fetchGroupAdminInfoResult("g1")).thenReturn(ImGroupFetchResult.ok(adminInfo("g1")));
        when(im.getRoleInGroupResult("g1", "u1"))
            .thenReturn(new ImRoleFetchResult("Member", ImRoleFetchResult.Status.OK, 0));
        when(im.getMemberNameCard("g1", "u1")).thenReturn(Optional.of("card"));
        when(memberRepository.findById(any())).thenReturn(Optional.empty());
        when(memberRepository.saveAndFlush(any(GroupMember.class))).thenAnswer(inv -> inv.getArgument(0));
        when(memberRepository.save(any(GroupMember.class))).thenAnswer(inv -> inv.getArgument(0));

        projection.hydrateGroupFromIm("g1", "u1");

        verify(memberRepository, never()).bulkSoftDeleteByGroupIdAndUserId(eq("g1"), eq("u1"));
        verify(memberRepository).saveAndFlush(any(GroupMember.class));
    }

    private static GroupProfile aliveProfile(String groupId) {
        GroupProfile p = new GroupProfile();
        p.setGroupId(groupId);
        p.setGroupType("Community");
        p.setDismissed(false);
        p.setMemberCount(10);
        return p;
    }

    private static GroupMember activeMember(String groupId, String userId) {
        GroupMember m = new GroupMember();
        m.setGroupId(groupId);
        m.setUserId(userId);
        m.setRole(GroupRoleCodec.MEMBER);
        m.setDeleted(false);
        return m;
    }

    private static GroupAdminInfo adminInfo(String groupId) {
        return new GroupAdminInfo(
            groupId, "n", "owner", 10, 100, 1L, null, "Off", "Community", null, null, "");
    }
}
