package com.chat99.server.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImAdminClient.AddGroupMemberResult;
import com.chat99.server.realtime.GroupRealtimePublisher;
import com.chat99.server.user.UserFriendService;
import com.chat99.server.user.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupJoinServiceApproveEmitTargetsTest {

    @Mock GroupSettingsRepository settingsRepository;
    @Mock GroupJoinApplicationRepository applicationRepository;
    @Mock GroupJoinApplicationDismissRepository applicationDismissRepository;
    @Mock GroupProfileRepository profileRepository;
    @Mock GroupMemberRepository memberRepository;
    @Mock GroupAccessService access;
    @Mock ImAdminClient im;
    @Mock UserFriendService friendService;
    @Mock UserRepository userRepository;
    @Mock GroupRealtimePublisher groupRealtime;
    @Mock GroupProjectionService groupProjection;
    @Mock GroupMemberEnrichmentService enrichment;
    @Mock GroupAvatarDefaults avatarDefaults;
    @Mock GroupChangeEmitter groupChangeEmitter;
    @Mock JoinApplicationsListCache joinApplicationsListCache;
    @Mock GroupJoinLimitService joinLimitService;
    @Mock ImGroupIdRemapLookup groupIdRemapLookup;
    @Mock GroupImSyncService groupImSyncService;

    GroupJoinService service;

    @BeforeEach
    void setUp() {
        lenient().when(avatarDefaults.resolve(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(enrichment.loadUserBriefs(anyCollection())).thenReturn(Map.of());
        service = new GroupJoinService(
            settingsRepository, applicationRepository, applicationDismissRepository,
            profileRepository, memberRepository, access, im, friendService, userRepository,
            groupRealtime, groupProjection, enrichment, avatarDefaults, groupChangeEmitter,
            joinApplicationsListCache, joinLimitService, groupIdRemapLookup,
            new GroupFanoutTargetResolver(groupProjection, im, GroupFanoutProperties.defaults()),
            groupImSyncService,
            false);
    }

    @Test
    void approve_usesLocalMemberTargets_andSkipsImListWhenLocalNonEmpty() {
        String groupId = "@TGS#_mc2SX4NMM62CZ";
        String adminId = "admin1";
        String applicantId = "u_new";
        long applicationId = 42L;

        GroupProfile profile = new GroupProfile();
        profile.setGroupId(groupId);
        profile.setGroupType("Community");
        profile.setGroupName("大群");

        GroupJoinApplication app = new GroupJoinApplication();
        app.setId(applicationId);
        app.setGroupId(groupId);
        app.setType(GroupJoinApplicationType.apply);
        app.setFromUserId(applicantId);
        app.setToUserId(applicantId);
        app.setStatus(GroupJoinApplicationStatus.pending);

        when(applicationRepository.findByIdAndGroupId(applicationId, groupId)).thenReturn(Optional.of(app));
        when(profileRepository.findById(groupId)).thenReturn(Optional.of(profile));
        when(access.isMember(groupId, applicantId)).thenReturn(false);
        when(groupProjection.listLocalMemberUserIds(groupId))
            .thenReturn(List.of(adminId, "u2", "u3", applicantId));
        when(memberRepository.findUserIdsByGroupIdAndRoleAtLeast(eq(groupId), eq(GroupRoleCodec.ADMIN)))
            .thenReturn(List.of(adminId, "admin2"));
        when(groupProjection.findProfile(groupId)).thenReturn(Optional.of(profile));
        when(applicationRepository.save(any(GroupJoinApplication.class))).thenAnswer(inv -> inv.getArgument(0));

        var resp = service.approveApplication(groupId, adminId, applicationId);

        assertThat(resp.status()).isEqualTo("approved");
        assertThat(resp.imResult()).isEqualTo(1);
        verify(groupProjection).onMembersJoined(
            eq(groupId), eq(List.of(applicantId)), eq(null), eq(GroupMemberJoinChannel.GROUP_ID));
        verify(groupImSyncService).syncAddMembers(eq(groupId), eq(List.of(applicantId)));
        verify(im, never()).addGroupMembers(anyString(), any(), any(Boolean.class));
        verify(im, never()).listGroupMemberUserIds(anyString(), anyInt());
        verify(im, never()).listGroupMemberRows(anyString(), anyInt(), anyInt());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> targetsCaptor = ArgumentCaptor.forClass(List.class);
        verify(groupChangeEmitter).emitMemberAdded(
            eq(groupId),
            eq(adminId),
            eq(List.of(applicantId)),
            targetsCaptor.capture(),
            any(),
            eq(GroupChangeEventSource.REST_APPROVE));
        assertThat(targetsCaptor.getValue()).containsExactlyInAnyOrder(adminId, "u2", "u3", applicantId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> handledTargetsCaptor = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detailCaptor = ArgumentCaptor.forClass(Map.class);
        verify(groupRealtime).publish(
            eq(groupId),
            eq(GroupRealtimePublisher.ACTION_JOIN_APPLICATION_HANDLED),
            eq(adminId),
            anyList(),
            handledTargetsCaptor.capture(),
            detailCaptor.capture(),
            anyString(),
            anyLong(),
            any());
        assertThat(handledTargetsCaptor.getValue())
            .containsExactlyInAnyOrder(adminId, "admin2", applicantId);
        assertThat(detailCaptor.getValue().get("operatorUserId")).isEqualTo(adminId);
        assertThat(detailCaptor.getValue()).containsKey("updatedAt");
    }

    @Test
    void approve_fallsBackToImList_whenLocalMembersEmpty() {
        String groupId = "@TGS#empty";
        String adminId = "admin1";
        String applicantId = "u_new";
        long applicationId = 7L;

        GroupProfile profile = new GroupProfile();
        profile.setGroupId(groupId);
        profile.setGroupType("Public");
        profile.setGroupName("空投影群");

        GroupJoinApplication app = new GroupJoinApplication();
        app.setId(applicationId);
        app.setGroupId(groupId);
        app.setType(GroupJoinApplicationType.apply);
        app.setFromUserId(applicantId);
        app.setToUserId(applicantId);
        app.setStatus(GroupJoinApplicationStatus.pending);

        when(applicationRepository.findByIdAndGroupId(applicationId, groupId)).thenReturn(Optional.of(app));
        when(profileRepository.findById(groupId)).thenReturn(Optional.of(profile));
        when(access.isMember(groupId, applicantId)).thenReturn(false);
        when(groupProjection.listLocalMemberUserIds(groupId)).thenReturn(List.of());
        when(im.listGroupMemberUserIds(eq(groupId), anyInt())).thenReturn(List.of(adminId, applicantId));
        when(memberRepository.findUserIdsByGroupIdAndRoleAtLeast(eq(groupId), eq(GroupRoleCodec.ADMIN)))
            .thenReturn(List.of(adminId));
        when(groupProjection.findProfile(groupId)).thenReturn(Optional.of(profile));
        when(applicationRepository.save(any(GroupJoinApplication.class))).thenAnswer(inv -> inv.getArgument(0));

        service.approveApplication(groupId, adminId, applicationId);

        verify(groupImSyncService).syncAddMembers(eq(groupId), eq(List.of(applicantId)));
        verify(im, never()).addGroupMembers(anyString(), any(), any(Boolean.class));
        verify(im).listGroupMemberUserIds(eq(groupId), anyInt());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> targetsCaptor = ArgumentCaptor.forClass(List.class);
        verify(groupChangeEmitter).emitMemberAdded(
            eq(groupId),
            eq(adminId),
            eq(List.of(applicantId)),
            targetsCaptor.capture(),
            any(),
            eq(GroupChangeEventSource.REST_APPROVE));
        assertThat(targetsCaptor.getValue()).containsExactlyInAnyOrder(adminId, applicantId);
    }

    @Test
    void reject_notifiesAllAdminsAndApplicant_withOperatorAndUpdatedAt() {
        String groupId = "@TGS#_reject";
        String operatorId = "admin1";
        String otherAdminId = "admin2";
        String applicantId = "u_apply";
        long applicationId = 99L;

        GroupJoinApplication app = new GroupJoinApplication();
        app.setId(applicationId);
        app.setGroupId(groupId);
        app.setType(GroupJoinApplicationType.apply);
        app.setFromUserId(applicantId);
        app.setToUserId(applicantId);
        app.setStatus(GroupJoinApplicationStatus.pending);

        when(applicationRepository.findByIdAndGroupId(applicationId, groupId)).thenReturn(Optional.of(app));
        when(memberRepository.findUserIdsByGroupIdAndRoleAtLeast(eq(groupId), eq(GroupRoleCodec.ADMIN)))
            .thenReturn(List.of(operatorId, otherAdminId));
        when(groupProjection.findProfile(groupId)).thenReturn(Optional.empty());
        when(applicationRepository.save(any(GroupJoinApplication.class))).thenAnswer(inv -> inv.getArgument(0));

        var resp = service.rejectApplication(groupId, operatorId, applicationId);

        assertThat(resp.status()).isEqualTo("rejected");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> targetsCaptor = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detailCaptor = ArgumentCaptor.forClass(Map.class);
        verify(groupRealtime).publish(
            eq(groupId),
            eq(GroupRealtimePublisher.ACTION_JOIN_APPLICATION_HANDLED),
            eq(operatorId),
            anyList(),
            targetsCaptor.capture(),
            detailCaptor.capture(),
            anyString(),
            anyLong(),
            any());
        assertThat(targetsCaptor.getValue())
            .containsExactlyInAnyOrder(operatorId, otherAdminId, applicantId);
        assertThat(detailCaptor.getValue().get("operatorUserId")).isEqualTo(operatorId);
        assertThat(detailCaptor.getValue().get("result")).isEqualTo("rejected");
        assertThat(detailCaptor.getValue()).containsKey("updatedAt");
        verify(im, never()).listGroupMemberRows(anyString(), anyInt(), anyInt());
    }
}
