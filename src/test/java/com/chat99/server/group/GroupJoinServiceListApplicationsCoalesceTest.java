package com.chat99.server.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.realtime.GroupRealtimePublisher;
import com.chat99.server.user.UserFriendService;
import com.chat99.server.user.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupJoinServiceListApplicationsCoalesceTest {

    private static final String USER = "user0001";
    private static final String GROUP = "@TGS#coalesce";

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
    void listApplications_concurrentSameKey_loadsOnce() throws Exception {
        GroupProfile profile = new GroupProfile();
        profile.setGroupId(GROUP);
        profile.setGroupType("Public");
        GroupMember admin = new GroupMember();
        admin.setGroupId(GROUP);
        admin.setUserId(USER);
        admin.setRole(GroupRoleCodec.ADMIN);

        when(groupProjection.findProfile(GROUP)).thenReturn(Optional.of(profile));
        when(groupProjection.findMember(GROUP, USER)).thenReturn(Optional.of(admin));
        when(joinApplicationsListCache.get(eq(GROUP), eq(USER), eq(false))).thenReturn(Optional.empty());

        AtomicInteger dbCalls = new AtomicInteger();
        CountDownLatch holdDb = new CountDownLatch(1);
        when(applicationRepository.findByGroupIdAndStatusExcludingDismissed(
            eq(GROUP), eq(GroupJoinApplicationStatus.pending), eq(USER)))
            .thenAnswer(inv -> {
                int n = dbCalls.incrementAndGet();
                if (n == 1) {
                    holdDb.await(2, TimeUnit.SECONDS);
                }
                return List.of();
            });

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Future<?> f1 = pool.submit(() -> {
                start.await(2, TimeUnit.SECONDS);
                service.listApplications(GROUP, USER, false);
                return null;
            });
            Future<?> f2 = pool.submit(() -> {
                start.await(2, TimeUnit.SECONDS);
                service.listApplications(GROUP, USER, false);
                return null;
            });
            Future<?> f3 = pool.submit(() -> {
                start.await(2, TimeUnit.SECONDS);
                service.listApplications(GROUP, USER, false);
                return null;
            });
            start.countDown();
            Thread.sleep(150);
            holdDb.countDown();
            f1.get(3, TimeUnit.SECONDS);
            f2.get(3, TimeUnit.SECONDS);
            f3.get(3, TimeUnit.SECONDS);
            assertThat(dbCalls.get()).isEqualTo(1);
            verify(joinApplicationsListCache, times(1)).put(eq(GROUP), eq(USER), eq(false), any());
        } finally {
            holdDb.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void listApplications_cacheHit_skipsDb() {
        GroupProfile profile = new GroupProfile();
        profile.setGroupId(GROUP);
        profile.setGroupType("Public");
        GroupMember admin = new GroupMember();
        admin.setGroupId(GROUP);
        admin.setUserId(USER);
        admin.setRole(GroupRoleCodec.ADMIN);
        when(groupProjection.findProfile(GROUP)).thenReturn(Optional.of(profile));
        when(groupProjection.findMember(GROUP, USER)).thenReturn(Optional.of(admin));

        var cached = new GroupJoinService.JoinApplicationListResponse(List.of());
        when(joinApplicationsListCache.get(GROUP, USER, false)).thenReturn(Optional.of(cached));

        assertThat(service.listApplications(GROUP, USER, false)).isSameAs(cached);
        verify(applicationRepository, times(0))
            .findByGroupIdAndStatusExcludingDismissed(anyString(), any(), anyString());
    }
}
