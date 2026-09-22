package com.chat99.server.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.restqueue.ImRestQueuePublisher;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class GroupMemberJoinAttributionTest {

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
    }

    @Test
    void onMembersJoined_writesInviteAttribution_whenRowFresh() {
        when(profileRepository.findById("g1")).thenReturn(Optional.of(aliveProfile("g1")));
        when(memberRepository.findById(any())).thenReturn(Optional.empty());
        when(im.countGroupMembers(anyString())).thenReturn(1);
        AtomicReference<GroupMember> saved = new AtomicReference<>();
        when(memberRepository.saveAndFlush(any(GroupMember.class))).thenAnswer(inv -> {
            GroupMember m = inv.getArgument(0);
            saved.set(m);
            return m;
        });
        when(memberRepository.save(any(GroupMember.class))).thenAnswer(inv -> {
            GroupMember m = inv.getArgument(0);
            saved.set(m);
            return m;
        });

        projection.onMembersJoined(
            "g1", List.of("userB"), "userA", GroupMemberJoinChannel.INVITE);

        assertThat(saved.get().getInvitedBy()).isEqualTo("userA");
        assertThat(saved.get().getJoinChannel()).isEqualTo(GroupMemberJoinChannel.INVITE);
        assertThat(saved.get().getJoinedAt()).isNotNull();
    }

    @Test
    void onMembersJoined_doesNotOverwriteExistingAttribution() {
        GroupMember existing = new GroupMember();
        existing.setGroupId("g1");
        existing.setUserId("userB");
        existing.setRole(GroupRoleCodec.MEMBER);
        existing.setInvitedBy("original");
        existing.setJoinChannel(GroupMemberJoinChannel.INVITE);
        when(profileRepository.findById("g1")).thenReturn(Optional.of(aliveProfile("g1")));
        when(memberRepository.findById(any())).thenReturn(Optional.of(existing));
        when(im.countGroupMembers(anyString())).thenReturn(1);
        when(memberRepository.save(any(GroupMember.class))).thenAnswer(inv -> inv.getArgument(0));

        projection.onMembersJoined(
            "g1", List.of("userB"), "other", GroupMemberJoinChannel.GROUP_ID);

        ArgumentCaptor<GroupMember> cap = ArgumentCaptor.forClass(GroupMember.class);
        verify(memberRepository).save(cap.capture());
        assertThat(cap.getValue().getInvitedBy()).isEqualTo("original");
        assertThat(cap.getValue().getJoinChannel()).isEqualTo(GroupMemberJoinChannel.INVITE);
        verify(memberRepository, never()).saveAndFlush(any());
    }

    @Test
    void onMembersJoined_groupIdChannel_withoutInviter() {
        when(profileRepository.findById("g1")).thenReturn(Optional.of(aliveProfile("g1")));
        when(memberRepository.findById(any())).thenReturn(Optional.empty());
        when(im.countGroupMembers(anyString())).thenReturn(1);
        AtomicReference<GroupMember> saved = new AtomicReference<>();
        when(memberRepository.saveAndFlush(any(GroupMember.class))).thenAnswer(inv -> {
            GroupMember m = inv.getArgument(0);
            saved.set(m);
            return m;
        });
        when(memberRepository.save(any(GroupMember.class))).thenAnswer(inv -> {
            GroupMember m = inv.getArgument(0);
            saved.set(m);
            return m;
        });

        projection.onMembersJoined(
            "g1", List.of("userC"), null, GroupMemberJoinChannel.GROUP_ID);

        assertThat(saved.get().getInvitedBy()).isNull();
        assertThat(saved.get().getJoinChannel()).isEqualTo(GroupMemberJoinChannel.GROUP_ID);
    }

    @Test
    void groupMemberView_exposesAttributionFields() {
        GroupMemberView view = new GroupMemberView(
            "u1", "im1", "nick", null, null, null, 200, "member", 1L, false,
            "inv1", "邀请人", GroupMemberJoinChannel.INVITE);
        assertThat(view.invitedByUserId()).isEqualTo("inv1");
        assertThat(view.invitedByNickname()).isEqualTo("邀请人");
        assertThat(view.joinChannel()).isEqualTo(GroupMemberJoinChannel.INVITE);
    }

    private static GroupProfile aliveProfile(String groupId) {
        GroupProfile p = new GroupProfile();
        p.setGroupId(groupId);
        p.setGroupType("Public");
        p.setDismissed(false);
        p.setMemberCount(1);
        return p;
    }
}
