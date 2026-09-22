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
class MeGroupNoticeChangesServiceTest {

    @Mock GroupNoticeInboxChangeRepository changeRepository;

    MeGroupNoticeChangesService service;

    @BeforeEach
    void setUp() {
        service = new MeGroupNoticeChangesService(changeRepository, new ObjectMapper());
    }

    @Test
    void cursorExpired_whenSinceBelowMinSeq() {
        when(changeRepository.findMinSeq()).thenReturn(100L);
        assertThatThrownBy(() -> service.listChangesBySeq("u1", 50L, 100))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> {
                ResponseStatusException rse = (ResponseStatusException) ex;
                assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.GONE);
                assertThat(rse.getReason()).isEqualTo("CURSOR_EXPIRED");
            });
    }

    @Test
    void mapsUpsertedDeletedAndWatermark() {
        when(changeRepository.findMinSeq()).thenReturn(1L);

        GroupNoticeInboxChange upserted = new GroupNoticeInboxChange();
        upserted.setSeq(10L);
        upserted.setUserId("u1");
        upserted.setEventType(GroupNoticeInboxChangeWriter.TYPE_NOTICE_UPSERTED);
        upserted.setNoticeId("n1");
        upserted.setPayloadJson(
            "{\"noticeId\":\"n1\",\"groupId\":\"g1\",\"groupName\":\"群\",\"groupAvatarUrl\":\"http://a\","
                + "\"noticeType\":\"grant_administrator\",\"type\":\"grant_administrator\","
                + "\"operatorUserId\":\"o1\",\"operatorNickName\":\"Op\",\"targetUserId\":\"t1\","
                + "\"targetNickName\":\"Tg\",\"createdAtMs\":99}");

        GroupNoticeInboxChange deleted = new GroupNoticeInboxChange();
        deleted.setSeq(11L);
        deleted.setUserId("u1");
        deleted.setEventType(GroupNoticeInboxChangeWriter.TYPE_NOTICE_DELETED);
        deleted.setNoticeId("n1");

        GroupNoticeInboxChange watermark = new GroupNoticeInboxChange();
        watermark.setSeq(12L);
        watermark.setUserId("u1");
        watermark.setEventType(GroupNoticeInboxChangeWriter.TYPE_READ_WATERMARK);
        watermark.setPayloadJson("{\"lastReadAtMs\":12345}");

        when(changeRepository.findForUserSinceSeq(eq("u1"), eq(5L), any(Pageable.class)))
            .thenReturn(List.of(upserted, deleted, watermark));

        var resp = service.listChangesBySeq("u1", 5L, 100);
        assertThat(resp.hasMore()).isFalse();
        assertThat(resp.nextSeq()).isEqualTo(12L);
        assertThat(resp.events()).hasSize(3);
        assertThat(resp.events().get(0).type()).isEqualTo("NOTICE_UPSERTED");
        assertThat(resp.events().get(0).noticeType()).isEqualTo("grant_administrator");
        assertThat(resp.events().get(0).groupName()).isEqualTo("群");
        assertThat(resp.events().get(1).type()).isEqualTo("NOTICE_DELETED");
        assertThat(resp.events().get(1).noticeId()).isEqualTo("n1");
        assertThat(resp.events().get(2).type()).isEqualTo("READ_WATERMARK");
        assertThat(resp.events().get(2).lastReadAtMs()).isEqualTo(12345L);
    }

    @Test
    void emptyPage_usesMaxSeq() {
        when(changeRepository.findMinSeq()).thenReturn(1L);
        when(changeRepository.findForUserSinceSeq(eq("u1"), eq(9L), any(Pageable.class)))
            .thenReturn(List.of());
        when(changeRepository.findMaxSeq()).thenReturn(40L);

        var resp = service.listChangesBySeq("u1", 9L, 100);
        assertThat(resp.events()).isEmpty();
        assertThat(resp.nextSeq()).isEqualTo(40L);
        assertThat(resp.hasMore()).isFalse();
    }
}
