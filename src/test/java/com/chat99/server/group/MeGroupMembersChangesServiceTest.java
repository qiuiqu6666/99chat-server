package com.chat99.server.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class MeGroupMembersChangesServiceTest {

    @Mock GroupMemberChangeRepository changeRepository;
    @Mock GroupAccessService access;
    @Mock GroupProjectionService projection;

    MeGroupMembersChangesService service;

    @BeforeEach
    void setUp() {
        service = new MeGroupMembersChangesService(
            changeRepository, access, projection, new ObjectMapper());
    }

    @Test
    void cursorExpired_whenSinceBelowMinSeq() {
        when(changeRepository.findMinSeqForGroup("g1")).thenReturn(100L);
        assertThatThrownBy(() -> service.listChangesBySeq("g1", "u1", 50L, 100))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> {
                ResponseStatusException rse = (ResponseStatusException) ex;
                assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.GONE);
                assertThat(rse.getReason()).isEqualTo("CURSOR_EXPIRED");
            });
    }

    @Test
    void mapsUpsertedAndRemoved() {
        when(changeRepository.findMinSeqForGroup("g1")).thenReturn(1L);
        GroupProfile profile = new GroupProfile();
        profile.setMemberCount(42);
        when(projection.findProfile("g1")).thenReturn(Optional.of(profile));

        GroupMemberChange upserted = new GroupMemberChange();
        upserted.setSeq(10L);
        upserted.setGroupId("g1");
        upserted.setEventType(GroupMemberChangeWriter.TYPE_MEMBER_UPSERTED);
        upserted.setUserId("u2");
        upserted.setMemberCount(42);
        upserted.setPayloadJson(
            "{\"userId\":\"u2\",\"nickName\":\"李四\",\"avatarUrl\":\"http://a\",\"role\":200}");

        GroupMemberChange removed = new GroupMemberChange();
        removed.setSeq(11L);
        removed.setGroupId("g1");
        removed.setEventType(GroupMemberChangeWriter.TYPE_MEMBER_REMOVED);
        removed.setUserId("u3");
        removed.setMemberCount(41);

        when(changeRepository.findForGroupSinceSeq(eq("g1"), eq(5L), any(Pageable.class)))
            .thenReturn(List.of(upserted, removed));

        var resp = service.listChangesBySeq("g1", "u1", 5L, 100);
        assertThat(resp.hasMore()).isFalse();
        assertThat(resp.nextSeq()).isEqualTo(11L);
        assertThat(resp.memberCount()).isEqualTo(42);
        assertThat(resp.events()).hasSize(2);
        assertThat(resp.events().get(0).type()).isEqualTo("MEMBER_UPSERTED");
        assertThat(resp.events().get(0).nickName()).isEqualTo("李四");
        assertThat(resp.events().get(0).role()).isEqualTo(200);
        assertThat(resp.events().get(1).type()).isEqualTo("MEMBER_REMOVED");
        assertThat(resp.events().get(1).userId()).isEqualTo("u3");
        assertThat(resp.events().get(1).memberCount()).isEqualTo(41);
    }
}
