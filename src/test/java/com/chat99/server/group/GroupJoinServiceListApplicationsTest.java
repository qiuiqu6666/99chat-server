package com.chat99.server.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.realtime.GroupRealtimePublisher;
import com.chat99.server.user.UserFriendService;
import com.chat99.server.user.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupJoinServiceListApplicationsTest {

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
        lenient().when(joinApplicationsListCache.get(anyString(), anyString(), anyBoolean()))
            .thenReturn(Optional.empty());
        lenient().when(avatarDefaults.resolve(any())).thenAnswer(inv -> inv.getArgument(0));
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
    void listApplications_usesLocalAuthAndSkipsImPortrait() {
        String groupId = "@TGS#1";
        String adminId = "admin1";
        GroupProfile profile = new GroupProfile();
        profile.setGroupId(groupId);
        profile.setGroupType("Public");
        profile.setGroupName("测试群");
        profile.setDismissed(false);

        GroupMember admin = new GroupMember();
        admin.setGroupId(groupId);
        admin.setUserId(adminId);
        admin.setRole(GroupRoleCodec.ADMIN);

        GroupJoinApplication app = new GroupJoinApplication();
        app.setId(9L);
        app.setGroupId(groupId);
        app.setType(GroupJoinApplicationType.apply);
        app.setFromUserId("u2");
        app.setToUserId("u2");
        app.setStatus(GroupJoinApplicationStatus.pending);

        when(groupProjection.findProfile(groupId)).thenReturn(Optional.of(profile));
        when(groupProjection.findMember(groupId, adminId)).thenReturn(Optional.of(admin));
        when(applicationRepository.findByGroupIdAndStatusExcludingDismissed(
            groupId, GroupJoinApplicationStatus.pending, adminId)).thenReturn(List.of(app));
        when(profileRepository.findAllById(org.mockito.ArgumentMatchers.<String>anySet())).thenReturn(List.of(profile));
        when(memberRepository.findByUserIdAndGroupIdIn(eq(adminId), org.mockito.ArgumentMatchers.<String>anySet()))
            .thenReturn(List.of(admin));
        when(enrichment.loadUserBriefs(org.mockito.ArgumentMatchers.<String>anySet())).thenReturn(
            java.util.Map.of("u2", new GroupMemberEnrichmentService.UserBrief("小二", null)));

        var resp = service.listApplications(groupId, adminId, false);

        assertThat(resp.items()).hasSize(1);
        assertThat(resp.items().get(0).fromUserNickName()).isEqualTo("小二");
        verify(im, never()).getPortraitProfiles(any());
        verify(access, never()).requireBackendInviteGroup(anyString());
        verify(access, never()).requireAdminRole(anyString(), anyString());
        verify(joinApplicationsListCache).put(eq(groupId), eq(adminId), eq(false), any());
    }

    @Test
    void listApplications_returnsCacheHit() {
        var cached = new GroupJoinService.JoinApplicationListResponse(List.of());
        when(groupProjection.findProfile("@TGS#c")).thenReturn(Optional.of(publicProfile("@TGS#c")));
        when(groupProjection.findMember("@TGS#c", "a")).thenReturn(Optional.of(adminMember("@TGS#c", "a")));
        when(joinApplicationsListCache.get("@TGS#c", "a", false)).thenReturn(Optional.of(cached));

        var resp = service.listApplications("@TGS#c", "a", false);

        assertThat(resp).isSameAs(cached);
        verify(applicationRepository, never()).findByGroupIdAndStatusExcludingDismissed(
            anyString(), any(), anyString());
    }

    private static GroupProfile publicProfile(String groupId) {
        GroupProfile p = new GroupProfile();
        p.setGroupId(groupId);
        p.setGroupType("Public");
        p.setDismissed(false);
        return p;
    }

    private static GroupMember adminMember(String groupId, String userId) {
        GroupMember m = new GroupMember();
        m.setGroupId(groupId);
        m.setUserId(userId);
        m.setRole(GroupRoleCodec.ADMIN);
        return m;
    }
}
