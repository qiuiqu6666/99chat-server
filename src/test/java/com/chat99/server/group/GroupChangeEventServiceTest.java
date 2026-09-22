package com.chat99.server.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.realtime.GroupRealtimePublisher;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class GroupChangeEventServiceTest {

    private static final String GROUP_ID = "@TGS#_abc";
    private static final String USER = "mem0001";

    @Mock GroupChangeEventRepository changeEventRepository;
    @Mock GroupChangeEventMapper mapper;
    @Mock GroupAccessService access;
    @Mock GroupProjectionService projection;

    @InjectMocks GroupChangeEventService service;

    @Test
    void listForGroup_returnsEventsInOrder() {
        GroupProfile profile = new GroupProfile();
        profile.setGroupId(GROUP_ID);
        profile.setDismissed(false);
        when(projection.findProfile(GROUP_ID)).thenReturn(Optional.of(profile));

        GroupChangeEvent row = new GroupChangeEvent();
        row.setChangeEventId("ce_1");
        row.setGroupId(GROUP_ID);
        row.setAction(GroupRealtimePublisher.ACTION_MEMBER_ADDED);
        row.setOccurredAt(100L);
        when(changeEventRepository.findByGroupSinceActions(
            eq(GROUP_ID), eq(0L), any(), any(Pageable.class)))
            .thenReturn(List.of(row));
        when(mapper.toItemView(row)).thenReturn(new GroupChangeEventService.ChangeEventItemView(
            "ce_1", GroupRealtimePublisher.ACTION_MEMBER_ADDED, "op", List.of("u2"), 100L, 30, java.util.Map.of()));

        GroupChangeEventService.GroupChangeEventsResponse resp =
            service.listForGroup(GROUP_ID, USER, 0, 50, null);

        assertThat(resp.groupId()).isEqualTo(GROUP_ID);
        assertThat(resp.items()).hasSize(1);
        assertThat(resp.nextSince()).isEqualTo(100L);
        assertThat(resp.hasMore()).isFalse();
        verify(access).requireMember(GROUP_ID, USER);
        verify(changeEventRepository).findByGroupSinceActions(
            eq(GROUP_ID), eq(0L), any(), any(Pageable.class));
    }

    @Test
    void listForGroup_whenDismissed_notFound() {
        GroupProfile profile = new GroupProfile();
        profile.setGroupId(GROUP_ID);
        profile.setDismissed(true);
        when(projection.findProfile(GROUP_ID)).thenReturn(Optional.of(profile));

        assertThatThrownBy(() -> service.listForGroup(GROUP_ID, USER, 0, 50, null))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getReason()).isEqualTo("GROUP_NOT_FOUND"));
    }
}
