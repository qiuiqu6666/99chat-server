package com.chat99.server.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.chat99.server.im.ImUserIdService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupJoinLimitServiceTest {

    @Mock GroupMemberRepository memberRepository;
    @Mock GroupCreateLimitConfigService config;
    @Mock UserOwnedGroupService ownedGroupService;
    @Mock ImUserIdService imUserIdService;

    GroupJoinLimitService service;

    @BeforeEach
    void setUp() {
        service = new GroupJoinLimitService(memberRepository, config, ownedGroupService, imUserIdService);
        lenient().when(imUserIdService.toBusinessForDisplay(org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(imUserIdService.toIm(org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void findOverLimit_usesSameUserIdWithoutMapping() {
        when(config.isEnabled()).thenReturn(true);
        when(config.joinLimitForGroupType("Work")).thenReturn(2);
        when(memberRepository.countNonCommunityGroupsByUserIds(any()))
            .thenReturn(java.util.List.<Object[]>of(new Object[] {"q14gkm5swv", 2L}));

        var over = service.findOverLimitUsers(List.of("q14gkm5swv"), "Work");
        assertThat(over).hasSize(1);
        assertThat(over.get(0).userId()).isEqualTo("q14gkm5swv");
        assertThat(over.get(0).used()).isEqualTo(2);
    }

    @Test
    void findOverLimit_nonCommunityBucket() {
        when(config.isEnabled()).thenReturn(true);
        when(config.joinLimitForGroupType("Work")).thenReturn(2);
        when(memberRepository.countNonCommunityGroupsByUserIds(any()))
            .thenReturn(java.util.List.<Object[]>of(new Object[] {"u1", 2L}, new Object[] {"u2", 1L}));

        var over = service.findOverLimitUsers(List.of("u1", "u2"), "Work");
        assertThat(over).hasSize(1);
        assertThat(over.get(0).userId()).isEqualTo("u1");
        assertThat(over.get(0).used()).isEqualTo(2);
        assertThat(over.get(0).max()).isEqualTo(2);
        assertThat(over.get(0).limitType()).isEqualTo(GroupJoinLimitService.LIMIT_TYPE_JOIN);
    }

    @Test
    void findOverLimit_communityBucket() {
        when(config.isEnabled()).thenReturn(true);
        when(config.joinLimitForGroupType("Community")).thenReturn(1000);
        when(memberRepository.countCommunityGroupsByUserIds(any()))
            .thenReturn(java.util.List.<Object[]>of(new Object[] {"u1", 1000L}));

        var over = service.findOverLimitUsers(List.of("u1"), "Community");
        assertThat(over).hasSize(1);
        assertThat(over.get(0).limitType()).isEqualTo(GroupJoinLimitService.LIMIT_TYPE_COMMUNITY_JOIN);
        assertThat(over.get(0).max()).isEqualTo(1000);
    }

    @Test
    void assertCanCreateGroup_rejectsCommunityCreateFirst() {
        when(config.isEnabled()).thenReturn(true);
        when(config.isEnforce()).thenReturn(true);
        when(ownedGroupService.canCreate("owner", "Community")).thenReturn(false);
        when(ownedGroupService.quota("owner", "Community"))
            .thenReturn(new UserOwnedGroupService.GroupCreateQuota(3, 3, 0));

        assertThatThrownBy(() ->
            service.assertCanCreateGroup("owner", "Community", List.of("u2")))
            .isInstanceOf(GroupJoinLimitExceededException.class)
            .satisfies(ex -> {
                GroupJoinLimitExceededException e = (GroupJoinLimitExceededException) ex;
                assertThat(e.getCode()).isEqualTo(GroupJoinLimitService.CODE_COMMUNITY_CREATE);
                assertThat(e.getOverLimitUsers()).hasSize(1);
                assertThat(e.getOverLimitUsers().get(0).limitType())
                    .isEqualTo(GroupJoinLimitService.LIMIT_TYPE_COMMUNITY_CREATE);
            });
    }

    @Test
    void assertUsersCanJoin_skipsAlreadyMembers() {
        when(config.isEnabled()).thenReturn(true);
        when(config.isEnforce()).thenReturn(true);
        when(config.joinLimitForGroupType(eq("Work"))).thenReturn(1);
        when(memberRepository.countNonCommunityGroupsByUserIds(any()))
            .thenReturn(java.util.List.<Object[]>of(new Object[] {"u2", 0L}));

        service.assertUsersCanJoin(List.of("u1", "u2"), "Work", List.of("u1"));
    }
}
