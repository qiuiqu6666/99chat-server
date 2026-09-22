package com.chat99.server.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImUserIdService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserOwnedGroupServiceTest {

    @Mock
    UserOwnedGroupRepository repository;
    @Mock
    ImAdminClient imAdmin;
    @Mock
    ImUserIdService imUserIdService;
    @Mock
    GroupCreateLimitConfigService config;

    UserOwnedGroupService service;

    @BeforeEach
    void setUp() {
        service = new UserOwnedGroupService(repository, imAdmin, imUserIdService, config);
        lenient().when(imUserIdService.toIm(org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void canCreateWhenBelowLimit() {
        when(config.isEnabled()).thenReturn(true);
        when(config.isUseImCountFallback()).thenReturn(true);
        when(config.limitForType("Community")).thenReturn(2);
        when(repository.countByOwnerUserIdAndGroupType("u1", "Community")).thenReturn(1L);
        when(imAdmin.countOwnedGroupsByTypes(eq("u1"), any())).thenReturn(Map.of("Community", 1));
        assertTrue(service.canCreate("u1", "Community"));
    }

    @Test
    void cannotCreateWhenAtLimit() {
        when(config.isEnabled()).thenReturn(true);
        when(config.isUseImCountFallback()).thenReturn(true);
        when(config.limitForType("Community")).thenReturn(1);
        when(repository.countByOwnerUserIdAndGroupType("u1", "Community")).thenReturn(0L);
        when(imAdmin.countOwnedGroupsByTypes(eq("u1"), any())).thenReturn(Map.of("Community", 1));
        assertFalse(service.canCreate("u1", "Community"));
    }

    @Test
    void unlimitedForWorkCreate() {
        when(config.isEnabled()).thenReturn(true);
        when(config.limitForType("Work")).thenReturn(-1);
        assertTrue(service.canCreate("u1", "Work"));
    }

    @Test
    void quotas_reusesSingleImCallForCommunity() {
        when(config.isUseImCountFallback()).thenReturn(true);
        when(config.limitForType("Community")).thenReturn(3);
        when(repository.countByOwnerUserIdAndGroupType("u1", "Community")).thenReturn(0L);
        when(imAdmin.countOwnedGroupsByTypes(eq("u1"), eq(List.of("Community"))))
            .thenReturn(Map.of("Community", 1));

        Map<String, UserOwnedGroupService.GroupCreateQuota> quotas =
            service.quotas("u1", List.of("Community"));

        assertThat(quotas.get("Community").used()).isEqualTo(1);
        assertThat(quotas.get("Community").remaining()).isEqualTo(2);
        verify(imAdmin, times(1)).countOwnedGroupsByTypes(eq("u1"), eq(List.of("Community")));
    }
}
