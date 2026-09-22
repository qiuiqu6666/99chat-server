package com.chat99.server.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class GroupJoinServiceDeleteMyApplicationsTest {

    private static final String USER = "user0001";
    private static final String GROUP_A = "@TGS#aaa";
    private static final String GROUP_B = "@TGS#bbb";

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
    void deleteMyApplications_emptyBody_batchInsertsAndDedupesCache() {
        GroupJoinApplication a1 = apply(1L, GROUP_A, USER);
        GroupJoinApplication a2 = apply(2L, GROUP_A, USER);
        GroupJoinApplication a3 = apply(3L, GROUP_B, USER);
        when(applicationRepository.findVisibleUndismissedApplicationIds(USER, null))
            .thenReturn(List.of(1L, 2L, 3L));
        when(applicationRepository.findAllById(List.of(1L, 2L, 3L)))
            .thenReturn(List.of(a1, a2, a3));
        when(applicationDismissRepository.findApplicationIdsByUserIdAndApplicationIdIn(eq(USER), any()))
            .thenReturn(List.of());
        when(applicationDismissRepository.insertIgnoreBatch(eq(USER), any(), any())).thenReturn(3);

        GroupJoinService.DeleteApplicationsResponse resp =
            service.deleteMyApplications(USER, List.of(), null);

        assertThat(resp.deleted()).isEqualTo(3);
        verify(applicationDismissRepository)
            .insertIgnoreBatch(eq(USER), eq(List.of(1L, 2L, 3L)), any());
        verify(joinApplicationsListCache, times(1)).invalidateGroup(GROUP_A);
        verify(joinApplicationsListCache, times(1)).invalidateGroup(GROUP_B);
    }

    @Test
    void deleteMyApplications_byIds_notVisible_notFound() {
        GroupJoinApplication other = apply(9L, GROUP_A, "other00001");
        when(applicationRepository.findAllById(List.of(9L))).thenReturn(List.of(other));
        when(groupProjection.findMember(GROUP_A, USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteMyApplications(USER, List.of(9L), null))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("APPLICATION_NOT_FOUND");
        verify(applicationDismissRepository, never()).insertIgnoreBatch(anyString(), any(), any());
        verify(joinApplicationsListCache, never()).invalidateGroup(anyString());
    }

    @Test
    void deleteMyApplications_byIds_alreadyDismissed_notFound() {
        GroupJoinApplication a1 = apply(1L, GROUP_A, USER);
        when(applicationRepository.findAllById(List.of(1L))).thenReturn(List.of(a1));
        when(applicationDismissRepository.findApplicationIdsByUserIdAndApplicationIdIn(eq(USER), any()))
            .thenReturn(List.of(1L));

        assertThatThrownBy(() -> service.deleteMyApplications(USER, List.of(1L), null))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("APPLICATION_NOT_FOUND");
        verify(applicationDismissRepository, never()).insertIgnoreBatch(anyString(), any(), any());
    }

    @Test
    void deleteMyApplications_emptyBody_zeroCandidates_ok() {
        when(applicationRepository.findVisibleUndismissedApplicationIds(USER, null))
            .thenReturn(List.of());

        GroupJoinService.DeleteApplicationsResponse resp =
            service.deleteMyApplications(USER, List.of(), null);

        assertThat(resp.deleted()).isEqualTo(0);
        verify(applicationDismissRepository, never()).insertIgnoreBatch(anyString(), any(), any());
    }

    private static GroupJoinApplication apply(long id, String groupId, String fromUserId) {
        GroupJoinApplication app = new GroupJoinApplication();
        app.setId(id);
        app.setGroupId(groupId);
        app.setType(GroupJoinApplicationType.apply);
        app.setFromUserId(fromUserId);
        app.setToUserId(fromUserId);
        app.setStatus(GroupJoinApplicationStatus.pending);
        return app;
    }
}
