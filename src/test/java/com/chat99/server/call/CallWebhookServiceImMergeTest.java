package com.chat99.server.call;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.common.AppSettingService;
import com.chat99.server.push.VoipCallPushTrigger;
import com.chat99.server.realtime.CallRecentRealtimePublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CallWebhookServiceImMergeTest {

    @Mock CallProperties props;
    @Mock AppSettingService settings;
    @Mock CallCallbackLogRepository callbackLogRepository;
    @Mock CallSessionRepository sessionRepository;
    @Mock CallRecordUserRepository recordRepository;
    @Mock VoipCallPushTrigger voipCallPushTrigger;
    @Mock CallRecentRealtimePublisher callRecentRealtimePublisher;

    private final ObjectMapper json = new ObjectMapper();
    private CallWebhookService service;

    @BeforeEach
    void setUp() {
        service = new CallWebhookService(
            props, settings, callbackLogRepository, sessionRepository, recordRepository, json,
            voipCallPushTrigger, callRecentRealtimePublisher);
        when(props.enabled()).thenReturn(true);
        when(callbackLogRepository.existsByIdempotencyKey(any())).thenReturn(false);
        Map<String, CallSession> sessions = new ConcurrentHashMap<>();
        when(sessionRepository.findById(any())).thenAnswer(inv -> Optional.ofNullable(sessions.get(inv.getArgument(0))));
        when(sessionRepository.save(any())).thenAnswer(inv -> {
            CallSession s = inv.getArgument(0);
            sessions.put(s.getCallId(), s);
            return s;
        });
        when(recordRepository.findByCallIdAndUserId(any(), any())).thenReturn(Optional.empty());
        when(recordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void tryMergeFromImBody_invite_createsProvisionalRecords() throws Exception {
        String inviteAv = """
            {"businessID":"av_call","call_end":0,"call_type":2,\
            "data":{"cmd":"videoCall","inviter":"caller1","room_id":100,"userIDs":["callee1"]},"room_id":100}
            """;
        String inviteData = json.writeValueAsString(Map.of(
            "actionType", 1,
            "inviteID", "invite-live",
            "inviteeList", List.of("callee1"),
            "inviter", "caller1",
            "data", inviteAv));

        service.tryMergeFromImBody(imBody("caller1", "callee1", 1_700_000_300_000L, inviteData));

        ArgumentCaptor<CallRecordUser> cap = ArgumentCaptor.forClass(CallRecordUser.class);
        verify(recordRepository, org.mockito.Mockito.atLeast(2)).save(cap.capture());
        assertThat(cap.getAllValues()).anyMatch(r ->
            "caller1".equals(r.getUserId()) && "outgoing".equals(r.getDirection())
                && r.getResult() == CallRecordResult.MISSED);
        assertThat(cap.getAllValues()).anyMatch(r ->
            "callee1".equals(r.getUserId()) && "incoming".equals(r.getDirection()));
        verify(callRecentRealtimePublisher).callEnded("invite-live", "caller1", "callee1");
    }

    @Test
    void tryMergeFromImBody_writesTerminalRejectRecord() throws Exception {
        String innerAv = """
            {"businessID":"av_call","call_end":2,"call_type":1,\
            "data":{"cmd":"reject","inviter":"caller1","room_id":123,\
            "userIDs":["callee1"]},"room_id":123}
            """;
        String data = json.writeValueAsString(Map.of(
            "actionType", 3,
            "inviteID", "invite-reject-1",
            "inviteeList", List.of("callee1"),
            "inviter", "caller1",
            "data", innerAv));

        Map<String, Object> imBody = new LinkedHashMap<>();
        imBody.put("From_Account", "caller1");
        imBody.put("To_Account", "callee1");
        imBody.put("EventTime", 1_700_000_000_000L);
        imBody.put("MsgBody", List.of(Map.of(
            "MsgType", "TIMCustomElem",
            "MsgContent", Map.of("Data", data))));

        service.tryMergeFromImBody(imBody);

        ArgumentCaptor<CallRecordUser> cap = ArgumentCaptor.forClass(CallRecordUser.class);
        verify(recordRepository, org.mockito.Mockito.atLeast(2)).save(cap.capture());
        assertThat(cap.getAllValues()).anyMatch(r ->
            "caller1".equals(r.getUserId()) && r.getResult() == CallRecordResult.REJECTED);
        assertThat(cap.getAllValues()).anyMatch(r ->
            "callee1".equals(r.getUserId()) && r.getResult() == CallRecordResult.REJECTED);
    }

    @Test
    void tryMergeFromImBody_actionType4Hangup_missedWhenNotAccepted() throws Exception {
        String innerAv = """
            {"businessID":"av_call","call_end":0,"call_type":1,\
            "data":{"cmd":"","inviter":"caller1","room_id":456,"userIDs":["callee1"]},"room_id":456}
            """;
        String data = json.writeValueAsString(Map.of(
            "actionType", 4,
            "inviteID", "invite-hangup-missed",
            "inviteeList", List.of("callee1"),
            "inviter", "caller1",
            "data", innerAv));

        Map<String, Object> imBody = new LinkedHashMap<>();
        imBody.put("From_Account", "caller1");
        imBody.put("To_Account", "callee1");
        imBody.put("EventTime", 1_700_000_100_000L);
        imBody.put("MsgBody", List.of(Map.of(
            "MsgType", "TIMCustomElem",
            "MsgContent", Map.of("Data", data))));

        service.tryMergeFromImBody(imBody);

        ArgumentCaptor<CallRecordUser> cap = ArgumentCaptor.forClass(CallRecordUser.class);
        verify(recordRepository, org.mockito.Mockito.atLeast(2)).save(cap.capture());
        assertThat(cap.getAllValues()).anyMatch(r ->
            "callee1".equals(r.getUserId()) && r.getResult() == CallRecordResult.MISSED);
    }

    @Test
    void finalizeStaleSessions_answeredWithoutHangup_durationZero() {
        CallSession session = new CallSession();
        session.setCallId("invite-finalize-1");
        session.setCallerUserId("caller1");
        session.setCalleeUserId("callee1");
        session.setAcceptedAt(Instant.ofEpochMilli(1_700_000_200_000L));
        session.setLastEventAt(Instant.ofEpochMilli(1_700_000_200_000L));
        session.setStartedAt(Instant.ofEpochMilli(1_700_000_190_000L));
        when(sessionRepository.findAll()).thenReturn(List.of());
        when(sessionRepository.findStaleAcceptedSessions(any())).thenReturn(List.of(session));
        when(sessionRepository.findStaleUnansweredSessions(any())).thenReturn(List.of());
        when(sessionRepository.markEndedIfOpen(any(), any(), any())).thenReturn(1);

        service.finalizeStaleSessions();

        ArgumentCaptor<CallRecordUser> cap = ArgumentCaptor.forClass(CallRecordUser.class);
        verify(recordRepository, org.mockito.Mockito.atLeast(2)).save(cap.capture());
        assertThat(cap.getAllValues()).allMatch(r ->
            r.getResult() == CallRecordResult.ANSWERED && r.getDurationSec() == 0);
    }

    @Test
    void tryMergeFromImBody_actionType4Hangup_answeredAfterAccept() throws Exception {
        String inviteAv = """
            {"businessID":"av_call","call_end":0,"call_type":1,\
            "data":{"cmd":"","inviter":"caller1","room_id":789,"userIDs":["callee1"]},"room_id":789}
            """;
        String acceptData = json.writeValueAsString(Map.of(
            "actionType", 2,
            "inviteID", "invite-answered",
            "inviteeList", List.of("callee1"),
            "inviter", "caller1",
            "data", inviteAv));
        String hangupData = json.writeValueAsString(Map.of(
            "actionType", 4,
            "inviteID", "invite-answered",
            "inviteeList", List.of("callee1"),
            "inviter", "caller1",
            "data", inviteAv));

        Map<String, Object> acceptBody = imBody("callee1", "caller1", 1_700_000_200_000L, acceptData);
        Map<String, Object> hangupBody = imBody("caller1", "callee1", 1_700_000_260_000L, hangupData);

        service.tryMergeFromImBody(acceptBody);
        service.tryMergeFromImBody(hangupBody);

        ArgumentCaptor<CallRecordUser> cap = ArgumentCaptor.forClass(CallRecordUser.class);
        verify(recordRepository, org.mockito.Mockito.atLeast(2)).save(cap.capture());
        assertThat(cap.getAllValues()).anyMatch(r ->
            "caller1".equals(r.getUserId()) && r.getResult() == CallRecordResult.ANSWERED && r.getDurationSec() == 60);
    }

    @Test
    void tryMergeFromImBody_callEndDuration_usesCallEndSeconds() throws Exception {
        String innerAv = """
            {"businessID":"av_call","call_end":18,"call_type":2,\
            "data":{"cmd":"hangup","inviter":"caller1","room_id":999,"userIDs":["callee1"]},"room_id":999}
            """;
        String data = json.writeValueAsString(Map.of(
            "actionType", 4,
            "inviteID", "invite-call-end-18",
            "inviteeList", List.of("callee1"),
            "inviter", "caller1",
            "data", innerAv));

        Map<String, Object> imBody = imBody("caller1", "callee1", 1_700_000_300_000L, data);
        service.tryMergeFromImBody(imBody);

        ArgumentCaptor<CallRecordUser> cap = ArgumentCaptor.forClass(CallRecordUser.class);
        verify(recordRepository, org.mockito.Mockito.atLeast(2)).save(cap.capture());
        assertThat(cap.getAllValues()).anyMatch(r ->
            "caller1".equals(r.getUserId()) && r.getResult() == CallRecordResult.ANSWERED && r.getDurationSec() == 18);
    }

    private Map<String, Object> imBody(String from, String to, long eventTime, String data) {
        Map<String, Object> imBody = new LinkedHashMap<>();
        imBody.put("From_Account", from);
        imBody.put("To_Account", to);
        imBody.put("EventTime", eventTime);
        imBody.put("MsgBody", List.of(Map.of(
            "MsgType", "TIMCustomElem",
            "MsgContent", Map.of("Data", data))));
        return imBody;
    }
}
