package com.chat99.server.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.realtime.GroupRealtimePublisher;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupSystemNoticeServiceInboxTest {

    @Mock GroupSystemNoticeRepository noticeRepository;
    @Mock GroupSystemNoticeDismissRepository dismissRepository;
    @Mock GroupNoticeReadStateRepository readStateRepository;
    @Mock GroupProfileRepository profileRepository;
    @Mock GroupProjectionService projection;
    @Mock GroupMemberEnrichmentService enrichment;
    @Mock GroupAvatarDefaults avatarDefaults;
    @Mock GroupRealtimePublisher groupRealtime;
    @Mock GroupSystemNoticesListCache listCache;
    @Mock GroupNoticeInboxChangeWriter inboxWriter;

    GroupSystemNoticeService service;
    AtomicLong seqCounter;

    @BeforeEach
    void setUp() {
        seqCounter = new AtomicLong(100);
        service = new GroupSystemNoticeService(
            noticeRepository, dismissRepository, readStateRepository, profileRepository,
            projection, enrichment, avatarDefaults, groupRealtime, listCache, inboxWriter);
    }

    @Test
    void recordAndPublish_writesInboxAndTcpPerTarget() {
        when(noticeRepository.existsRecent(anyString(), any(), anyString(), anyString(), any()))
            .thenReturn(false);
        when(noticeRepository.existsById(anyString())).thenReturn(false);
        when(noticeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(profileRepository.findAllById(any())).thenReturn(List.of());
        when(enrichment.loadUserBriefs(any())).thenReturn(Map.of());
        when(inboxWriter.writeUpserted(anyString(), anyString(), anyMap()))
            .thenAnswer(inv -> {
                long seq = seqCounter.getAndIncrement();
                return new GroupNoticeInboxChangeWriter.WriteResult(seq, seq, "evt-" + seq);
            });

        service.recordAndPublish(
            "g1", GroupSystemNoticeType.grant_administrator, "op1", "tg1");

        verify(inboxWriter).writeUpserted(eq("op1"), anyString(), anyMap());
        verify(inboxWriter).writeUpserted(eq("tg1"), anyString(), anyMap());

        ArgumentCaptor<Map<String, Object>> detailCap = ArgumentCaptor.forClass(Map.class);
        verify(groupRealtime, org.mockito.Mockito.times(2)).publish(
            eq("g1"),
            eq(GroupRealtimePublisher.ACTION_GROUP_SYSTEM_NOTICE),
            eq("op1"),
            eq(List.of()),
            anyList(),
            detailCap.capture(),
            anyString(),
            anyLong(),
            isNull());

        assertThat(detailCap.getAllValues()).allSatisfy(detail -> {
            assertThat(detail.get("noticeId")).isNotNull();
            assertThat(detail.get("seq")).isInstanceOf(Long.class);
            assertThat(detail.get("type")).isEqualTo("grant_administrator");
            assertThat(detail.get("noticeType")).isEqualTo("grant_administrator");
        });
    }

    @Test
    void dismiss_writesDeletedEvents() {
        GroupSystemNotice row = new GroupSystemNotice();
        row.setNoticeId("n1");
        row.setGroupId("g1");
        row.setOperatorUserId("op1");
        row.setTargetUserId("u1");
        row.setType(GroupSystemNoticeType.grant_administrator);
        row.setCreatedAt(Instant.now());
        when(noticeRepository.findAllById(List.of("n1"))).thenReturn(List.of(row));
        when(dismissRepository.findNoticeIdsByUserIdAndNoticeIdIn(eq("u1"), anyList()))
            .thenReturn(List.of());
        when(inboxWriter.writeDeleted(eq("u1"), eq("n1")))
            .thenReturn(new GroupNoticeInboxChangeWriter.WriteResult(55L, 55L, "evt1"));
        when(noticeRepository.findById("n1")).thenReturn(Optional.of(row));

        var resp = service.dismissNotice("u1", "n1");
        assertThat(resp.deleted()).isEqualTo(1);
        verify(dismissRepository).insertIgnoreBatch(eq("u1"), eq(List.of("n1")), any());
        verify(inboxWriter).writeDeleted("u1", "n1");
        verify(groupRealtime).publish(
            eq("g1"),
            eq(GroupRealtimePublisher.ACTION_GROUP_SYSTEM_NOTICE),
            eq("op1"),
            eq(List.of()),
            eq(List.of("u1")),
            anyMap(),
            anyString(),
            anyLong(),
            isNull());
    }

    @Test
    void markRead_writesWatermark() {
        when(readStateRepository.findById("u1")).thenReturn(Optional.empty());
        when(readStateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(inboxWriter.writeReadWatermark(eq("u1"), eq(42L))).thenReturn(9L);

        service.markRead("u1", 42L);

        verify(inboxWriter).writeReadWatermark("u1", 42L);
        verify(groupRealtime, never()).publish(
            anyString(), anyString(), anyString(), anyList(), anyList(), anyMap(),
            anyString(), anyLong(), any());
    }
}
