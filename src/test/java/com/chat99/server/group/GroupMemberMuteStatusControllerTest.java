package com.chat99.server.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.im.ImAdminClient;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import com.chat99.server.im.ImUserIdService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class GroupMemberMuteStatusControllerTest {

    @Mock GroupMemberService memberService;
    @Mock GroupAccessService access;
    @Mock GroupProjectionService projection;
    @Mock ImUserIdService imUserIdService;
    @Mock MeGroupMembersChangesService membersChangesService;

    GroupMemberController controller;

    @BeforeEach
    void setUp() {
        controller = new GroupMemberController(
            memberService, access, projection, imUserIdService, membersChangesService);
    }

    @Test
    void muteStatus_readsLocalProjection_notIm() {
        when(projection.activeMutedUntil("g1", "u1")).thenReturn(Optional.of(2_000_000_000L));
        when(projection.isShutUpAll("g1")).thenReturn(true);

        var auth = new UsernamePasswordAuthenticationToken("u1", null);
        GroupMemberController.MuteStatusResponse resp = controller.getMyMuteStatus("g1", auth);

        assertThat(resp.userId()).isEqualTo("u1");
        assertThat(resp.muteUntil()).isEqualTo(2_000_000_000L);
        assertThat(resp.isAllMuted()).isTrue();
        verify(access).requireMember("g1", "u1");
        verify(projection).activeMutedUntil("g1", "u1");
        verify(projection).isShutUpAll("g1");
    }

    @Test
    void mutedMembers_readsLocalActivelyMuted() {
        GroupMember row = new GroupMember();
        row.setGroupId("g1");
        row.setUserId("u2");
        row.setRole(GroupRoleCodec.MEMBER);
        row.setNameCard("n");
        row.setMutedUntil(Instant.now().getEpochSecond() + 600);
        when(projection.listActivelyMutedMembers("g1")).thenReturn(List.of(row));
        when(projection.isShutUpAll("g1")).thenReturn(false);
        when(imUserIdService.toBusinessForDisplay("u2")).thenReturn("u2");

        var auth = new UsernamePasswordAuthenticationToken("u1", null);
        GroupMemberController.MutedMembersResponse resp = controller.getMutedMembers("g1", auth);

        assertThat(resp.members()).hasSize(1);
        ImAdminClient.MutedMemberInfo info = resp.members().get(0);
        assertThat(info.userId()).isEqualTo("u2");
        assertThat(info.imRole()).isEqualTo("Member");
        assertThat(resp.isAllMuted()).isFalse();
        verify(access).requireMember(eq("g1"), eq("u1"));
    }
}
