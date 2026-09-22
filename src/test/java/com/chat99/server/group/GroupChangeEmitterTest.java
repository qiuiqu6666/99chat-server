package com.chat99.server.group;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.realtime.GroupRealtimePublisher;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupChangeEmitterTest {

    private static final String GROUP_ID = "@TGS#_abc";
    private static final String OPERATOR = "userA";
    private static final String FRIEND = "userB";

    @Mock ImAdminClient im;
    @Mock GroupRealtimePublisher groupRealtime;
    @Mock GroupProjectionService projection;
    @Mock GroupAvatarDefaults avatarDefaults;
    @Mock GroupChangeEventRepository changeEventRepository;
    @Mock GroupChangeEventMapper changeEventMapper;
    @Mock GroupDisplaySeqAllocator seqAllocator;
    @Mock GroupMemberChangeWriter memberChangeWriter;
    @Mock GroupMemberEnrichmentService enrichment;
    @Mock GroupFanoutTargetResolver fanoutTargets;
    @Mock GroupChangeRevisionSeqAllocator revisionAllocator;

    GroupChangeEmitter emitter;

    @BeforeEach
    void setUp() {
        when(seqAllocator.nextSeq()).thenReturn(1L, 2L, 3L, 4L, 5L);
        when(revisionAllocator.next()).thenReturn(1L, 2L, 3L, 4L, 5L);
        emitter = new GroupChangeEmitter(
            im, groupRealtime, projection, avatarDefaults, changeEventRepository, changeEventMapper,
            seqAllocator, memberChangeWriter, enrichment, fanoutTargets, revisionAllocator,
            new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    void emitMemberAdded_usesSameChangeEventIdForExistingAndJoiner() {
        Instant occurredAt = Instant.parse("2026-06-25T00:00:00Z");
        GroupProfile profile = new GroupProfile();
        profile.setGroupId(GROUP_ID);
        profile.setGroupName("测试群");
        profile.setGroupType("Public");
        profile.setDisplayAlias("@ABC");
        profile.setMemberCount(2);
        GroupMember member = new GroupMember();
        member.setGroupId(GROUP_ID);
        member.setUserId(FRIEND);
        member.setRole(200);
        member.setJoinedAt(occurredAt);
        when(projection.findProfile(GROUP_ID)).thenReturn(Optional.of(profile));
        when(projection.findMember(GROUP_ID, FRIEND)).thenReturn(Optional.of(member));
        when(avatarDefaults.resolve(any())).thenReturn("https://avatar");
        when(enrichment.loadUserBriefs(any())).thenReturn(Map.of());
        when(memberChangeWriter.writeUpsertedBatch(eq(GROUP_ID), eq(List.of(FRIEND)), eq(2), any()))
            .thenReturn(99L);

        emitter.emitMemberAdded(
            GROUP_ID,
            OPERATOR,
            List.of(FRIEND),
            List.of(OPERATOR, FRIEND),
            occurredAt,
            GroupChangeEventSource.IM_CALLBACK);

        verify(memberChangeWriter).writeUpsertedBatch(eq(GROUP_ID), eq(List.of(FRIEND)), eq(2), any());
        verify(groupRealtime, times(2)).publish(
            eq(GROUP_ID),
            eq(GroupRealtimePublisher.ACTION_MEMBER_ADDED),
            eq(OPERATOR),
            eq(List.of(FRIEND)),
            any(),
            org.mockito.ArgumentMatchers.argThat(map -> Long.valueOf(99L).equals(map.get("seq"))),
            any(),
            eq(occurredAt.toEpochMilli()),
            eq(30));
        verify(changeEventRepository).save(any(GroupChangeEvent.class));
    }

    @Test
    void emitMemberLeftOrRemoved_setsTimelineRank40() {
        Instant occurredAt = Instant.parse("2026-06-25T00:00:01Z");
        when(projection.findProfile(GROUP_ID)).thenReturn(Optional.of(profile(1)));
        when(memberChangeWriter.writeRemovedBatch(eq(GROUP_ID), eq(List.of(OPERATOR)), eq(1)))
            .thenReturn(55L);

        emitter.emitMemberLeftOrRemoved(
            GROUP_ID,
            GroupRealtimePublisher.ACTION_MEMBER_LEFT,
            OPERATOR,
            List.of(OPERATOR),
            List.of("other"),
            occurredAt,
            GroupChangeEventSource.IM_CALLBACK);

        verify(memberChangeWriter).writeRemovedBatch(eq(GROUP_ID), eq(List.of(OPERATOR)), eq(1));
        verify(groupRealtime).publish(
            eq(GROUP_ID),
            eq(GroupRealtimePublisher.ACTION_MEMBER_LEFT),
            eq(OPERATOR),
            eq(List.of(OPERATOR)),
            eq(List.of("other")),
            org.mockito.ArgumentMatchers.argThat(map -> Long.valueOf(55L).equals(map.get("seq"))),
            any(),
            eq(occurredAt.toEpochMilli()),
            eq(40));
    }

    @Test
    void emitGroupChanged_alignsDetailOccurredAt() {
        Instant occurredAt = Instant.parse("2026-06-25T00:00:02Z");
        Map<String, Object> detail = GroupRealtimeDetailFactory.memberCountChanged(3, List.of(FRIEND), occurredAt);

        emitter.emitGroupChanged(
            GROUP_ID,
            GroupRealtimePublisher.ACTION_MEMBER_REMOVED,
            OPERATOR,
            List.of(FRIEND),
            List.of(OPERATOR, FRIEND),
            detail,
            occurredAt,
            GroupChangeEventSource.IM_CALLBACK);

        verify(groupRealtime).publish(
            eq(GROUP_ID),
            eq(GroupRealtimePublisher.ACTION_MEMBER_REMOVED),
            eq(OPERATOR),
            eq(List.of(FRIEND)),
            eq(List.of(OPERATOR, FRIEND)),
            org.mockito.ArgumentMatchers.argThat(map ->
                Long.valueOf(occurredAt.toEpochMilli()).equals(map.get("updatedAt"))
                    && Long.valueOf(occurredAt.toEpochMilli()).equals(map.get("occurredAt"))),
            any(),
            eq(occurredAt.toEpochMilli()),
            eq(40));
    }

    @Test
    void emitGroupDismissed_emptyTargetsUsesOperatorAndPersistsAudit() {
        Instant occurredAt = Instant.parse("2026-06-25T00:00:03Z");

        emitter.emitGroupDismissed(
            GROUP_ID,
            OPERATOR,
            List.of(),
            occurredAt,
            GroupChangeEventSource.REST_DISMISS);

        verify(changeEventRepository).save(any(GroupChangeEvent.class));
        verify(groupRealtime).publish(
            eq(GROUP_ID),
            eq(GroupRealtimePublisher.ACTION_GROUP_DISMISSED),
            eq(OPERATOR),
            eq(List.of()),
            eq(List.of(OPERATOR)),
            any(),
            any(),
            eq(occurredAt.toEpochMilli()),
            any());
    }

    private static GroupProfile profile(int count) {
        GroupProfile profile = new GroupProfile();
        profile.setGroupId(GROUP_ID);
        profile.setMemberCount(count);
        return profile;
    }
}
