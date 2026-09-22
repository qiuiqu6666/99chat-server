package com.chat99.server.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.im.ImUserIdService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class GroupMemberServiceTest {

    @Mock GroupProjectionService projection;
    @Mock GroupMemberRepository memberRepository;
    @Mock GroupAccessService access;
    @Mock GroupMemberEnrichmentService enrichment;
    @Mock ImUserIdService imUserIdService;

    private GroupMemberService service;

    @BeforeEach
    void setUp() {
        service = new GroupMemberService(projection, memberRepository, access, enrichment, imUserIdService);
        GroupProfile profile = new GroupProfile();
        profile.setGroupId("g1");
        profile.setMemberCount(3);
        when(projection.findProfile("g1")).thenReturn(Optional.of(profile));
        lenient().when(memberRepository.countActiveByGroupId("g1")).thenReturn(3L);
        lenient().when(imUserIdService.toBusinessForDisplayBatch(anyList())).thenAnswer(inv -> {
            List<String> ids = inv.getArgument(0);
            return ids.stream().collect(java.util.stream.Collectors.toMap(id -> id, id -> id));
        });
        lenient().when(imUserIdService.toBusinessForDisplay(anyString())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(imUserIdService.findImUserId(anyString())).thenReturn(Optional.empty());
        lenient().when(enrichment.loadUserBriefs(anyList())).thenReturn(Map.of());
        lenient().when(enrichment.loadFriendRemarks(anyString(), anyList())).thenReturn(Map.of());
    }

    @Test
    void listMembers_preservesOwnerAdminMemberOrderAndIdentity() {
        when(memberRepository.findListByGroupId(anyString(), any())).thenReturn(List.of(
            member("owner", GroupRoleCodec.OWNER),
            member("admin", GroupRoleCodec.ADMIN),
            member("member", GroupRoleCodec.MEMBER)));

        GroupMemberService.GroupMembersResponse response = service.listMembers("g1", "member", 100, 0, false, null);

        assertThat(response.items()).extracting(GroupMemberView::userId)
            .containsExactly("owner", "admin", "member");
        assertThat(response.items()).extracting(GroupMemberView::roleName)
            .containsExactly("owner", "admin", "member");
        assertThat(response.total()).isEqualTo(3);
    }

    @Test
    void listMembers_admins_returnsOwnerAndAdminOnly() {
        when(memberRepository.countActiveByGroupIdAndRoleAtLeast("g1", GroupRoleCodec.ADMIN)).thenReturn(2L);
        when(memberRepository.findListByGroupIdAndRoleAtLeast(eq("g1"), eq(GroupRoleCodec.ADMIN), any()))
            .thenReturn(List.of(
                member("owner", GroupRoleCodec.OWNER),
                member("admin", GroupRoleCodec.ADMIN)));

        GroupMemberService.GroupMembersResponse response =
            service.listMembers("g1", "member", 100, 0, false, "admins");

        assertThat(response.items()).extracting(GroupMemberView::userId)
            .containsExactly("owner", "admin");
        assertThat(response.total()).isEqualTo(2);
        verify(memberRepository, never()).findListByGroupId(anyString(), any());
        verify(memberRepository, never()).findListByGroupIdAndRoleBelow(anyString(), anyInt(), any());
    }

    @Test
    void listMembers_members_returnsRegularMembersOnly() {
        when(memberRepository.countActiveByGroupIdAndRoleBelow("g1", GroupRoleCodec.ADMIN)).thenReturn(1L);
        when(memberRepository.findListByGroupIdAndRoleBelow(eq("g1"), eq(GroupRoleCodec.ADMIN), any()))
            .thenReturn(List.of(member("member", GroupRoleCodec.MEMBER)));

        GroupMemberService.GroupMembersResponse response =
            service.listMembers("g1", "member", 100, 0, false, "members");

        assertThat(response.items()).extracting(GroupMemberView::userId)
            .containsExactly("member");
        assertThat(response.total()).isEqualTo(1);
        verify(memberRepository, never()).findListByGroupId(anyString(), any());
        verify(memberRepository, never()).findListByGroupIdAndRoleAtLeast(anyString(), anyInt(), any());
    }

    @Test
    void listMembers_unknownRole_fallsBackToAllMembers() {
        when(memberRepository.findListByGroupId(anyString(), any())).thenReturn(List.of(
            member("owner", GroupRoleCodec.OWNER),
            member("admin", GroupRoleCodec.ADMIN),
            member("member", GroupRoleCodec.MEMBER)));

        GroupMemberService.GroupMembersResponse response =
            service.listMembers("g1", "member", 100, 0, false, "owner");

        assertThat(response.items()).extracting(GroupMemberView::userId)
            .containsExactly("owner", "admin", "member");
        assertThat(response.total()).isEqualTo(3);
        verify(memberRepository, never()).findListByGroupIdAndRoleAtLeast(anyString(), anyInt(), any());
        verify(memberRepository, never()).findListByGroupIdAndRoleBelow(anyString(), anyInt(), any());
    }

    @Test
    void getInviter_returnsInviteAttribution() {
        GroupMember target = member("userB", GroupRoleCodec.MEMBER);
        target.setInvitedBy("userA");
        target.setJoinChannel(GroupMemberJoinChannel.INVITE);
        when(memberRepository.findByGroupIdAndUserIdActive("g1", "userB")).thenReturn(Optional.of(target));
        when(enrichment.loadUserBriefs(List.of("userA")))
            .thenReturn(Map.of("userA", new GroupMemberEnrichmentService.UserBrief("邀请人", null)));

        GroupMemberService.GroupMemberInviterResponse resp =
            service.getInviter("g1", "caller", "userB");

        assertThat(resp.groupId()).isEqualTo("g1");
        assertThat(resp.userId()).isEqualTo("userB");
        assertThat(resp.invitedByUserId()).isEqualTo("userA");
        assertThat(resp.invitedByNickname()).isEqualTo("邀请人");
        assertThat(resp.joinChannel()).isEqualTo(GroupMemberJoinChannel.INVITE);
        verify(access).requireMember("g1", "caller");
        verify(memberRepository, never()).findListByGroupId(anyString(), any());
    }

    @Test
    void getInviter_nullWhenNoInviter() {
        GroupMember target = member("userC", GroupRoleCodec.MEMBER);
        target.setJoinChannel(GroupMemberJoinChannel.GROUP_ID);
        when(memberRepository.findByGroupIdAndUserIdActive("g1", "userC")).thenReturn(Optional.of(target));

        GroupMemberService.GroupMemberInviterResponse resp =
            service.getInviter("g1", "caller", "userC");

        assertThat(resp.invitedByUserId()).isNull();
        assertThat(resp.invitedByNickname()).isNull();
        assertThat(resp.joinChannel()).isEqualTo(GroupMemberJoinChannel.GROUP_ID);
        verify(enrichment, never()).loadUserBriefs(anyList());
    }

    @Test
    void getInviter_targetNotMember_notFound() {
        when(memberRepository.findByGroupIdAndUserIdActive("g1", "ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getInviter("g1", "caller", "ghost"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> {
                ResponseStatusException rse = (ResponseStatusException) ex;
                assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(rse.getReason()).isEqualTo("NOT_GROUP_MEMBER");
            });
    }

    private static GroupMember member(String userId, int role) {
        GroupMember member = new GroupMember();
        member.setGroupId("g1");
        member.setUserId(userId);
        member.setRole(role);
        member.setDeleted(false);
        return member;
    }
}
