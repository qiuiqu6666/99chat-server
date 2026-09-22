package com.chat99.server.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class MeGroupsChangesServiceTest {

    @Mock GroupChangeEventRepository changeEventRepository;

    MeGroupsChangesService service;

    @BeforeEach
    void setUp() {
        service = new MeGroupsChangesService(changeEventRepository, new ObjectMapper());
    }

    @Test
    void cursorExpired_whenSinceBelowMinSeq() {
        when(changeEventRepository.findMinGroupSeq()).thenReturn(100L);
        assertThatThrownBy(() -> service.listChangesBySeq("u1", 50L, 100))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> {
                ResponseStatusException rse = (ResponseStatusException) ex;
                assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.GONE);
                assertThat(rse.getReason()).isEqualTo("CURSOR_EXPIRED");
            });
    }

    @Test
    void mapsDisplayEvents_andNextSeq() throws Exception {
        when(changeEventRepository.findMinGroupSeq()).thenReturn(1L);
        GroupChangeEvent row = new GroupChangeEvent();
        row.setChangeEventId("ev1");
        row.setGroupId("m1");
        row.setAction("group_name_changed");
        row.setGroupSeq(10L);
        row.setDetailJson("{\"groupName\":\"N\",\"avatarUrl\":\"http://a\",\"avatarVersion\":3,\"updatedAt\":99}");
        when(changeEventRepository.streamGroupAfter(eq("u1"), eq(5L), any(Pageable.class)))
            .thenReturn(List.of(row));

        var resp = service.listChangesBySeq("u1", 5L, 100);
        assertThat(resp.hasMore()).isFalse();
        assertThat(resp.nextSeq()).isEqualTo(5L);
        assertThat(resp.events()).isEmpty();
    }
}
