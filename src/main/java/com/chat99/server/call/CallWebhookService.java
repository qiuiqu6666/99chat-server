/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.call;

import com.chat99.server.call.AvCallImSignalingParser;
import com.chat99.server.call.CallCallbackLog;
import com.chat99.server.call.CallCallbackLogRepository;
import com.chat99.server.call.CallProperties;
import com.chat99.server.call.CallRecordResult;
import com.chat99.server.call.CallRecordUser;
import com.chat99.server.call.CallRecordUserRepository;
import com.chat99.server.call.CallSession;
import com.chat99.server.call.CallSessionRepository;
import com.chat99.server.call.CallSessionStatus;
import com.chat99.server.call.TrtcCallResultMapper;
import com.chat99.server.common.AppSettingService;
import com.chat99.server.push.VoipCallPushTrigger;
import com.chat99.server.realtime.CallRecentRealtimePublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/*
 * Exception performing whole class analysis ignored.
 */
@Service
public class CallWebhookService {
    private static final Logger log = LoggerFactory.getLogger(CallWebhookService.class);
    private final CallProperties props;
    private final AppSettingService settings;
    private final CallCallbackLogRepository callbackLogRepository;
    private final CallSessionRepository sessionRepository;
    private final CallRecordUserRepository recordRepository;
    private final ObjectMapper json;
    private final VoipCallPushTrigger voipCallPushTrigger;
    private final CallRecentRealtimePublisher callRecentRealtimePublisher;

    public CallWebhookService(CallProperties props, AppSettingService settings, CallCallbackLogRepository callbackLogRepository, CallSessionRepository sessionRepository, CallRecordUserRepository recordRepository, ObjectMapper json, VoipCallPushTrigger voipCallPushTrigger, CallRecentRealtimePublisher callRecentRealtimePublisher) {
        this.props = props;
        this.settings = settings;
        this.callbackLogRepository = callbackLogRepository;
        this.sessionRepository = sessionRepository;
        this.recordRepository = recordRepository;
        this.json = json;
        this.voipCallPushTrigger = voipCallPushTrigger;
        this.callRecentRealtimePublisher = callRecentRealtimePublisher;
    }

    @Transactional
    public TrtcCallbackResponse handle(String sdkAppId, String command, String clientIp, String optPlatform, String callbackToken, String rawBody) {
        Map<String, Object> body = this.parseBody(rawBody);
        String resolvedCommand = CallWebhookService.firstNonBlank(command, CallWebhookService.str(body.get("CallbackCommand")));
        boolean v2 = CallWebhookService.isV2Callback(resolvedCommand, body);
        Optional<AvCallImSignalingParser.ParsedEvent> avCall = AvCallImSignalingParser.tryParse((ObjectMapper)this.json, body);
        if (!this.props.enabled()) {
            // Keep endpoint for Tencent retries, but do not VoIP / merge when TRTC is fully off.
            return this.responseOk(v2, avCall.isPresent());
        }
        avCall.ifPresent(this.voipCallPushTrigger::tryFromParsedEvent);
        this.validateSdkAppId(sdkAppId);
        this.validateToken(callbackToken);
        String callId = CallWebhookService.extractCallId(body, v2, avCall.orElse(null));
        long eventTime = CallWebhookService.extractEventTime(body, v2, avCall.orElse(null));
        String eventUserId = CallWebhookService.extractEventUserId(body, v2, avCall.orElse(null));
        String idempotencyKey = CallWebhookService.buildIdempotencyKey(callId, resolvedCommand, eventUserId, eventTime, v2, avCall.orElse(null));
        if (this.callbackLogRepository.existsByIdempotencyKey(idempotencyKey)) {
            log.info("trtc callback duplicate idempotencyKey={}", idempotencyKey);
            return this.responseOk(v2, avCall.isPresent());
        }
        CallCallbackLog logEntry = new CallCallbackLog();
        logEntry.setCallId(callId);
        logEntry.setCommand(resolvedCommand);
        logEntry.setEventUserId(eventUserId);
        logEntry.setPayloadJson(this.buildStoredPayload(sdkAppId, resolvedCommand, clientIp, optPlatform, rawBody));
        logEntry.setIdempotencyKey(idempotencyKey);
        logEntry.setProcessed(false);
        try {
            if (avCall.isPresent() && avCall.get().terminal()) {
                this.mergeAvCallImCallback(avCall.get(), body);
            } else if (v2) {
                this.mergeV2Callback(body);
            } else if (CallWebhookService.isLegacyCallEnd(resolvedCommand, body)) {
                this.mergeLegacyCallback(body);
            } else if (avCall.isPresent()) {
                log.debug("trtc av_call non-terminal inviteId={} callEnd={}", avCall.get().inviteId(), avCall.get().callEnd());
            }
            logEntry.setProcessed(true);
        }
        catch (Exception e) {
            logEntry.setProcessError(CallWebhookService.truncate(e.getMessage(), 255));
            log.warn("trtc callback merge failed callId={} command={}: {}", new String[]{callId, resolvedCommand, e.getMessage()});
        }
        this.callbackLogRepository.save(logEntry);
        return this.responseOk(v2, avCall.isPresent());
    }

    public void reprocessStoredPayload(String wrappedPayloadJson) {
        try {
            Map wrap = (Map)this.json.readValue(wrappedPayloadJson, Map.class);
            String rawBody = CallWebhookService.str(wrap.get("body"));
            if (rawBody == null) {
                return;
            }
            Map<String, Object> body = this.parseBody(rawBody);
            AvCallImSignalingParser.tryParse((ObjectMapper)this.json, body).filter(AvCallImSignalingParser.ParsedEvent::terminal).ifPresent(event -> this.mergeAvCallImCallback((AvCallImSignalingParser.ParsedEvent)event, body));
        }
        catch (Exception e) {
            log.warn("av_call backfill skip: {}", (Object)e.getMessage());
        }
    }

    @Transactional
    public void tryMergeFromImBody(Map<String, Object> imBody) {
        if (!this.props.enabled() || imBody == null) {
            return;
        }
        AvCallImSignalingParser.tryParse((ObjectMapper)this.json, imBody).ifPresent(event -> {
            if (event.terminal()) {
                String dedupKey = CallWebhookService.avCallTerminalDedupKey(event);
                if (this.callbackLogRepository.existsByIdempotencyKey(dedupKey)) {
                    log.debug("av_call im duplicate dedupKey={}", (Object)dedupKey);
                    return;
                }
                try {
                    this.mergeAvCallImCallback((AvCallImSignalingParser.ParsedEvent)event, imBody);
                    CallCallbackLog logEntry = new CallCallbackLog();
                    logEntry.setCallId(event.inviteId());
                    logEntry.setCommand("C2C.CallbackAfterSendMsg.av_call");
                    logEntry.setEventUserId(event.callerId());
                    logEntry.setIdempotencyKey(dedupKey);
                    logEntry.setProcessed(true);
                    logEntry.setPayloadJson(this.toJson(imBody));
                    this.callbackLogRepository.save(logEntry);
                }
                catch (Exception e) {
                    log.warn("av_call im merge failed inviteId={}: {}", (Object)event.inviteId(), (Object)e.getMessage());
                }
            } else {
                this.touchAvCallProgress((AvCallImSignalingParser.ParsedEvent)event);
            }
        });
    }

    private TrtcCallbackResponse responseOk(boolean v2, boolean imStyle) {
        if (v2 || imStyle) {
            return TrtcCallbackResponse.v2Ok();
        }
        return TrtcCallbackResponse.legacyOk();
    }

    private static boolean isLegacyCallEnd(String command, Map<String, Object> body) {
        if ("call_end".equalsIgnoreCase(command)) {
            return true;
        }
        return body.get("CallId") != null && body.get("UserId") != null;
    }

    private void mergeAvCallImCallback(AvCallImSignalingParser.ParsedEvent event, Map<String, Object> body) {
        String callId = event.inviteId();
        CallSession session = this.loadOrCreateSession(callId);
        if (session.getStartedAt() == null) {
            session.setStartedAt(Instant.ofEpochMilli(event.eventTimeMs()));
        }
        session.setRoomId(event.roomId());
        session.setCallType("single");
        session.setMediaType(AvCallImSignalingParser.normalizeMediaType((int)event.callType()));
        session.setCallerUserId(event.callerId());
        session.setCalleeUserId(event.calleeId());
        CallWebhookService.touchLastEventAt(session, Instant.ofEpochMilli(event.eventTimeMs()));
        boolean accepted = session.getAcceptedAt() != null;
        CallRecordResult result = AvCallImSignalingParser.resolveHangupResult((AvCallImSignalingParser.ParsedEvent)event, (boolean)accepted);
        Instant occurredAt = Instant.ofEpochMilli(event.eventTimeMs());
        session.setEndedAt(occurredAt);
        session.setStatus(TrtcCallResultMapper.toSessionStatus((CallRecordResult)result));
        session.setRawPayloadJson(this.toJson(body));
        this.sessionRepository.save(session);
        int durationSec = CallWebhookService.avCallDurationSec(session, result, occurredAt, event);
        this.upsertUserRecord(callId, event.callerId(), event.calleeId(), "caller", "outgoing", result, durationSec, occurredAt);
        this.upsertUserRecord(callId, event.calleeId(), event.callerId(), "callee", "incoming", result, durationSec, occurredAt);
        this.callRecentRealtimePublisher.callEnded(callId, event.callerId(), event.calleeId());
        log.info("av_call merged inviteId={} caller={} callee={} callEnd={} actionType={} result={} durationSec={}",
            callId, event.callerId(), event.calleeId(), event.callEnd(), event.actionType(), result, durationSec);
    }

    private void touchAvCallProgress(AvCallImSignalingParser.ParsedEvent event) {
        CallSession session = this.loadOrCreateSession(event.inviteId());
        if (session.getEndedAt() != null) {
            return;
        }
        Instant at = Instant.ofEpochMilli(event.eventTimeMs());
        boolean changed = CallWebhookService.touchLastEventAt(session, at);
        if (AvCallImSignalingParser.isAcceptSignal((AvCallImSignalingParser.ParsedEvent)event) && session.getAcceptedAt() == null) {
            session.setAcceptedAt(at);
            changed = true;
            this.upsertProvisionalCallRecords(event, CallRecordResult.ANSWERED);
        }
        if (AvCallImSignalingParser.isIncomingInvite((AvCallImSignalingParser.ParsedEvent)event)) {
            if (session.getStartedAt() == null) {
                session.setStartedAt(at);
                changed = true;
            }
            if (session.getRoomId() == null && event.roomId() != null) {
                session.setRoomId(event.roomId());
                changed = true;
            }
            if (session.getCallType() == null) {
                session.setCallType("single");
                changed = true;
            }
            String media = AvCallImSignalingParser.normalizeMediaType((int)event.callType());
            if (session.getMediaType() == null) {
                session.setMediaType(media);
                changed = true;
            }
            if (session.getCallerUserId() == null) {
                session.setCallerUserId(event.callerId());
                changed = true;
            }
            if (session.getCalleeUserId() == null) {
                session.setCalleeUserId(event.calleeId());
                changed = true;
            }
            this.upsertProvisionalCallRecords(event, CallRecordResult.MISSED);
        }
        if (changed) {
            this.sessionRepository.save(session);
        }
    }

    private void upsertProvisionalCallRecords(AvCallImSignalingParser.ParsedEvent event, CallRecordResult result) {
        String callId = event.inviteId();
        String callerId = event.callerId();
        String calleeId = event.calleeId();
        if (callId == null || callerId == null || calleeId == null) {
            return;
        }
        Instant at = Instant.ofEpochMilli(event.eventTimeMs());
        this.upsertUserRecord(callId, callerId, calleeId, "caller", "outgoing", result, 0, at);
        this.upsertUserRecord(callId, calleeId, callerId, "callee", "incoming", result, 0, at);
        this.callRecentRealtimePublisher.callEnded(callId, callerId, calleeId);
    }

    private static boolean touchLastEventAt(CallSession session, Instant at) {
        if (at == null) {
            return false;
        }
        if (session.getLastEventAt() != null && !at.isAfter(session.getLastEventAt())) {
            return false;
        }
        session.setLastEventAt(at);
        return true;
    }

    private static int avCallDurationSec(CallSession session, CallRecordResult result, Instant endedAt, AvCallImSignalingParser.ParsedEvent event) {
        Instant accept;
        if (result != CallRecordResult.ANSWERED) {
            return 0;
        }
        if (event != null && event.callEnd() > 5) {
            return event.callEnd();
        }
        if (endedAt == null) {
            return 0;
        }
        Instant instant = accept = session.getAcceptedAt() != null ? session.getAcceptedAt() : session.getStartedAt();
        if (accept == null) {
            return 0;
        }
        long sec = endedAt.getEpochSecond() - accept.getEpochSecond();
        return sec > 0L ? (int)sec : 0;
    }

    private static String avCallTerminalDedupKey(AvCallImSignalingParser.ParsedEvent event) {
        if (event.callEnd() > 0) {
            return event.inviteId() + "|av_call_end|" + event.callEnd();
        }
        return event.inviteId() + "|av_call_end|action" + event.actionType();
    }

    private Map<String, Object> parseBody(String rawBody) {
        try {
            Map parsed = (Map)this.json.readValue(rawBody, Map.class);
            return parsed;
        }
        catch (JsonProcessingException e) {
            log.warn("trtc callback invalid json: {}", (Object)e.getMessage());
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "INVALID_JSON");
        }
    }

    private static boolean isV2Callback(String command, Map<String, Object> body) {
        if (command != null && command.startsWith("Call.")) {
            return true;
        }
        String bodyCommand = CallWebhookService.str(body.get("CallbackCommand"));
        return bodyCommand != null && bodyCommand.startsWith("Call.");
    }

    private static String extractCallId(Map<String, Object> body, boolean v2, AvCallImSignalingParser.ParsedEvent avCall) {
        if (avCall != null) {
            return avCall.inviteId();
        }
        if (v2) {
            Map<String, Object> record = CallWebhookService.map(body.get("CallRecord"));
            return record == null ? CallWebhookService.str(body.get("CallId")) : CallWebhookService.str(record.get("CallId"));
        }
        return CallWebhookService.str(body.get("CallId"));
    }

    private static long extractEventTime(Map<String, Object> body, boolean v2, AvCallImSignalingParser.ParsedEvent avCall) {
        if (avCall != null) {
            return avCall.eventTimeMs();
        }
        return CallWebhookService.longVal(body.get("EventTime"));
    }

    private static String extractEventUserId(Map<String, Object> body, boolean v2, AvCallImSignalingParser.ParsedEvent avCall) {
        if (avCall != null) {
            return avCall.callerId();
        }
        if (v2) {
            Map<String, Object> record = CallWebhookService.map(body.get("CallRecord"));
            if (record != null) {
                return CallWebhookService.str(record.get("Caller_Account"));
            }
            return null;
        }
        return CallWebhookService.str(body.get("UserId"));
    }

    private static String buildIdempotencyKey(String callId, String command, String eventUserId, long eventTime, boolean v2, AvCallImSignalingParser.ParsedEvent avCall) {
        if (avCall != null && avCall.terminal()) {
            return CallWebhookService.avCallTerminalDedupKey(avCall);
        }
        if (avCall != null) {
            return callId + "|av_call|" + command + "|" + eventTime;
        }
        if (v2) {
            return callId + "|" + command + "|" + eventTime;
        }
        return callId + "|" + command + "|" + eventUserId + "|" + eventTime;
    }

    private void mergeLegacyCallback(Map<String, Object> body) {
        String callType = TrtcCallResultMapper.normalizeCallType((String)CallWebhookService.str(body.get("CallType")));
        if (!TrtcCallResultMapper.isSingleCall((String)callType)) {
            log.info("trtc callback skip non-single callType={} callId={}", (Object)callType, (Object)CallWebhookService.str(body.get("CallId")));
            return;
        }
        String callId = CallWebhookService.str(body.get("CallId"));
        if (callId == null || callId.isBlank()) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD");
        }
        String userId = CallWebhookService.str(body.get("UserId"));
        String role = CallWebhookService.str(body.get("Role"));
        CallRecordResult result = TrtcCallResultMapper.toRecordResult((String)CallWebhookService.str(body.get("CallResult")));
        CallSession session = this.loadOrCreateSession(callId);
        session.setRoomId(CallWebhookService.str(body.get("RoomId")));
        session.setCallType(callType);
        session.setMediaType(TrtcCallResultMapper.normalizeMediaType((String)CallWebhookService.str(body.get("MediaType"))));
        if ("caller".equalsIgnoreCase(role)) {
            session.setCallerUserId(userId);
        } else if ("callee".equalsIgnoreCase(role)) {
            session.setCalleeUserId(userId);
        }
        session.setStartedAt(CallWebhookService.instantSec(body.get("StartCallTs")));
        session.setAcceptedAt(CallWebhookService.instantSec(body.get("AcceptTs")));
        session.setEndedAt(CallWebhookService.firstNonNull(CallWebhookService.instantSec(body.get("EndTs")), CallWebhookService.instantSec(body.get("EventTime"))));
        session.setStatus(TrtcCallResultMapper.toSessionStatus((CallRecordResult)result));
        session.setRawPayloadJson(this.toJson(body));
        this.sessionRepository.save(session);
        String direction = "caller".equalsIgnoreCase(role) ? "outgoing" : "incoming";
        String peerUserId = CallWebhookService.resolvePeer(session, userId, role);
        int durationSec = CallWebhookService.answeredDuration(result, CallWebhookService.longObj(body.get("AcceptTs")), CallWebhookService.longObj(body.get("EndTs")));
        Instant occurredAt = CallWebhookService.firstNonNull(CallWebhookService.instantSec(body.get("EndTs")), CallWebhookService.instantSec(body.get("EventTime")), Instant.now());
        this.upsertUserRecord(callId, userId, peerUserId, role, direction, result, durationSec, occurredAt);
        this.backfillPeerRecords(callId, session);
        this.sessionRepository.findById(callId).ifPresent(s -> {
            if (s.getCallerUserId() != null && s.getCalleeUserId() != null) {
                this.callRecentRealtimePublisher.callEnded(callId, s.getCallerUserId(), s.getCalleeUserId());
            }
        });
    }

    private void mergeV2Callback(Map<String, Object> body) {
        Map<String, Object> record = CallWebhookService.map(body.get("CallRecord"));
        if (record == null) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD");
        }
        String callType = TrtcCallResultMapper.normalizeCallType((String)CallWebhookService.str(record.get("CallType")));
        if (!TrtcCallResultMapper.isSingleCall((String)callType)) {
            log.info("trtc callback skip non-single callType={} callId={}", (Object)callType, (Object)CallWebhookService.str(record.get("CallId")));
            return;
        }
        String callId = CallWebhookService.str(record.get("CallId"));
        if (callId == null || callId.isBlank()) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD");
        }
        String callerId = CallWebhookService.str(record.get("Caller_Account"));
        String calleeId = CallWebhookService.resolveCallee(callerId, record.get("CalleeList_Account"));
        if (callerId == null || callerId.isBlank() || calleeId == null || calleeId.isBlank()) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD");
        }
        CallRecordResult result = TrtcCallResultMapper.toRecordResult((String)CallWebhookService.str(record.get("CallResult")));
        Long acceptSec = CallWebhookService.longObj(record.get("AcceptTime"));
        Long endSec = CallWebhookService.longObj(record.get("EndTime"));
        int durationSec = CallWebhookService.answeredDuration(result, acceptSec, endSec);
        Instant occurredAt = CallWebhookService.firstNonNull(CallWebhookService.instantSec(record.get("EndTime")), CallWebhookService.instantMillis(body.get("EventTime")), Instant.now());
        CallSession session = this.loadOrCreateSession(callId);
        session.setRoomId(CallWebhookService.str(record.get("RoomId")));
        session.setCallType(callType);
        session.setMediaType(TrtcCallResultMapper.normalizeMediaType((String)CallWebhookService.str(record.get("MediaType"))));
        session.setCallerUserId(callerId);
        session.setCalleeUserId(calleeId);
        session.setStartedAt(CallWebhookService.instantSec(record.get("StartTime")));
        session.setAcceptedAt(acceptSec != null && acceptSec > 0L ? CallWebhookService.instantSec(acceptSec) : null);
        session.setEndedAt(CallWebhookService.instantSec(record.get("EndTime")));
        session.setStatus(TrtcCallResultMapper.toSessionStatus((CallRecordResult)result));
        session.setRawPayloadJson(this.toJson(body));
        this.sessionRepository.save(session);
        this.upsertUserRecord(callId, callerId, calleeId, "caller", "outgoing", result, durationSec, occurredAt);
        this.upsertUserRecord(callId, calleeId, callerId, "callee", "incoming", result, durationSec, occurredAt);
        this.callRecentRealtimePublisher.callEnded(callId, callerId, calleeId);
    }

    private CallSession loadOrCreateSession(String callId) {
        return this.sessionRepository.findById(callId).orElseGet(() -> {
            CallSession s = new CallSession();
            s.setCallId(callId);
            return s;
        });
    }

    private void upsertUserRecord(String callId, String userId, String peerUserId, String role, String direction, CallRecordResult result, int durationSec, Instant occurredAt) {
        CallRecordUser record = this.recordRepository.findByCallIdAndUserId(callId, userId).orElseGet(() -> {
            CallRecordUser r = new CallRecordUser();
            r.setCallId(callId);
            r.setUserId(userId);
            r.setDeleted(false);
            return r;
        });
        record.setPeerUserId(peerUserId);
        record.setRole(role);
        record.setDirection(direction);
        if (record.getId() != null && record.getOccurredAt() != null && occurredAt != null && occurredAt.isBefore(record.getOccurredAt())) {
            boolean terminalDurationFix;
            boolean bl = terminalDurationFix = durationSec > 0 && record.getDurationSec() > Math.max(60, durationSec * 3);
            if (!terminalDurationFix) {
                return;
            }
        }
        record.setResult(result);
        record.setDurationSec(durationSec);
        record.setOccurredAt(occurredAt);
        this.recordRepository.save(record);
    }

    @Transactional
    public void finalizeStaleSessions() {
        Instant now = Instant.now();
        for (CallSession session : this.sessionRepository.findStaleAcceptedSessions(now.minusSeconds(20L))) {
            // LiveKit 以 room_finished / hangup 收尾；20s 误杀会导致 token 失效与媒体失步
            if (session.getCallType() != null && "livekit".equalsIgnoreCase(session.getCallType())) {
                continue;
            }
            this.finalizeOpenSession(session, CallRecordResult.ANSWERED, now);
        }
        for (CallSession session : this.sessionRepository.findStaleUnansweredSessions(now.minusSeconds(60L))) {
            if (session.getCallerUserId() == null || session.getCalleeUserId() == null) continue;
            this.finalizeOpenSession(session, CallRecordResult.MISSED, now);
        }
    }

    /**
     * 修复因 hangup/webhook 竞态导致的膨胀 ANSWERED 时长。
     * 只扫近期 ended 且 accepted→ended &gt; 60s 的会话（见 {@link CallSessionRepository#findInflatedAnsweredSessionsForRepair}）。
     */
    @Transactional
    public void repairInflatedFinalizeDurations() {
        Instant since = Instant.now().minusSeconds(2L * 24 * 3600);
        for (CallSession session : this.sessionRepository.findInflatedAnsweredSessionsForRepair(since)) {
            long gapSec;
            if (session.getEndedAt() == null || session.getAcceptedAt() == null || (gapSec = session.getEndedAt().getEpochSecond() - session.getAcceptedAt().getEpochSecond()) <= 60L) continue;
            Instant fixedOccurred = session.getLastEventAt() != null ? session.getLastEventAt() : session.getAcceptedAt();
            for (String userId : List.of(session.getCallerUserId(), session.getCalleeUserId())) {
                if (userId == null) continue;
                this.recordRepository.findByCallIdAndUserId(session.getCallId(), userId).ifPresent(record -> {
                    if (record.getResult() != CallRecordResult.ANSWERED || record.isDeleted()) {
                        return;
                    }
                    if (record.getDurationSec() <= 60 || (long)record.getDurationSec() != gapSec) {
                        return;
                    }
                    record.setDurationSec(0);
                    record.setOccurredAt(fixedOccurred);
                    this.recordRepository.save(record);
                    log.info("av_call repaired inflated duration inviteId={} userId={} oldDurationSec={}",
                        session.getCallId(), userId, gapSec);
                });
            }
        }
    }

    private void finalizeOpenSession(CallSession session, CallRecordResult result, Instant fallbackEndedAt) {
        if (session.getEndedAt() != null) {
            return;
        }
        String callId = session.getCallId();
        String callerId = session.getCallerUserId();
        String calleeId = session.getCalleeUserId();
        if (callId == null || callerId == null || calleeId == null) {
            return;
        }
        Instant occurredAt = session.getLastEventAt() != null ? session.getLastEventAt() : (session.getAcceptedAt() != null ? session.getAcceptedAt() : (session.getStartedAt() != null ? session.getStartedAt() : fallbackEndedAt));
        CallSessionStatus status = TrtcCallResultMapper.toSessionStatus((CallRecordResult)result);
        int won = this.sessionRepository.markEndedIfOpen(callId, occurredAt, status);
        if (won == 0) {
            return;
        }
        session.setEndedAt(occurredAt);
        session.setStatus(status);
        int durationSec = 0;
        this.upsertUserRecord(callId, callerId, calleeId, "caller", "outgoing", result, durationSec, occurredAt);
        this.upsertUserRecord(callId, calleeId, callerId, "callee", "incoming", result, durationSec, occurredAt);
        this.callRecentRealtimePublisher.callEnded(callId, callerId, calleeId);
        log.info("av_call finalized inviteId={} caller={} callee={} result={} durationSec={} occurredAt={}",
            callId, callerId, calleeId, result, durationSec, occurredAt);
    }

    private static int answeredDuration(CallRecordResult result, Long acceptSec, Long endSec) {
        if (result != CallRecordResult.ANSWERED) {
            return 0;
        }
        if (acceptSec != null && endSec != null && endSec > acceptSec) {
            return (int)(endSec - acceptSec);
        }
        return 0;
    }

    private static String resolveCallee(String callerId, Object calleeListRaw) {
        if (!(calleeListRaw instanceof List)) {
            return null;
        }
        List list = (List)calleeListRaw;
        for (Object item : list) {
            String account = CallWebhookService.str(item);
            if (account == null || account.isBlank() || account.equals(callerId)) continue;
            return account;
        }
        if (list.size() == 1) {
            return CallWebhookService.str(list.get(0));
        }
        return null;
    }

    private void backfillPeerRecords(String callId, CallSession session) {
        if (session.getCallerUserId() == null || session.getCalleeUserId() == null) {
            return;
        }
        this.recordRepository.findByCallIdAndUserId(callId, session.getCallerUserId()).ifPresent(r -> {
            if (r.getPeerUserId() == null) {
                r.setPeerUserId(session.getCalleeUserId());
                this.recordRepository.save(r);
            }
        });
        this.recordRepository.findByCallIdAndUserId(callId, session.getCalleeUserId()).ifPresent(r -> {
            if (r.getPeerUserId() == null) {
                r.setPeerUserId(session.getCallerUserId());
                this.recordRepository.save(r);
            }
        });
    }

    private static String resolvePeer(CallSession session, String userId, String role) {
        if ("caller".equalsIgnoreCase(role) && session.getCalleeUserId() != null) {
            return session.getCalleeUserId();
        }
        if ("callee".equalsIgnoreCase(role) && session.getCallerUserId() != null) {
            return session.getCallerUserId();
        }
        if (session.getCallerUserId() != null && !session.getCallerUserId().equals(userId)) {
            return session.getCallerUserId();
        }
        if (session.getCalleeUserId() != null && !session.getCalleeUserId().equals(userId)) {
            return session.getCalleeUserId();
        }
        return null;
    }

    private String toJson(Map<String, Object> body) {
        try {
            return this.json.writeValueAsString(body);
        }
        catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private void validateSdkAppId(String sdkAppId) {
        if (sdkAppId == null || sdkAppId.isBlank()) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
        Set<String> allowed = this.allowedSdkAppIds();
        if (!allowed.isEmpty() && !allowed.contains(sdkAppId.trim())) {
            log.warn("trtc callback rejected sdkAppId={}", (Object)sdkAppId);
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
    }

    private Set<String> allowedSdkAppIds() {
        String csv = this.props.allowedSdkAppIds();
        if (csv != null && !csv.isBlank()) {
            return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        }
        int configured = this.settings.getInt("IM_SDK_APP_ID", 0);
        if (configured != 0) {
            return Set.of(String.valueOf(configured));
        }
        return Set.of();
    }

    private void validateToken(String token) {
        String expected = this.props.callbackToken();
        if (expected == null || expected.isBlank()) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
        if (token == null || !expected.equals(token)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
    }

    private String buildStoredPayload(String sdkAppId, String command, String clientIp, String optPlatform, String rawBody) {
        LinkedHashMap<String, String> wrap = new LinkedHashMap<String, String>();
        wrap.put("sdkappid", sdkAppId);
        wrap.put("command", command);
        wrap.put("clientip", clientIp);
        wrap.put("optplatform", optPlatform);
        wrap.put("body", rawBody);
        try {
            return this.json.writeValueAsString(wrap);
        }
        catch (JsonProcessingException e) {
            return rawBody;
        }
    }

    private static Map<String, Object> map(Object o) {
        if (o instanceof Map) {
            Map m = (Map)o;
            return m;
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String firstNonBlank(String ... values) {
        for (String v : values) {
            if (v == null || v.isBlank()) continue;
            return v;
        }
        return null;
    }

    private static long longVal(Object o) {
        if (o instanceof Number) {
            Number n = (Number)o;
            return n.longValue();
        }
        if (o == null) {
            return 0L;
        }
        try {
            return Long.parseLong(o.toString());
        }
        catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static Long longObj(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            Number n = (Number)o;
            return n.longValue();
        }
        try {
            return Long.parseLong(o.toString());
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant instantSec(Object o) {
        Long sec = CallWebhookService.longObj(o);
        if (sec == null || sec <= 0L) {
            return null;
        }
        return Instant.ofEpochSecond(sec);
    }

    private static Instant instantMillis(Object o) {
        Long ms = CallWebhookService.longObj(o);
        if (ms == null || ms <= 0L) {
            return null;
        }
        return Instant.ofEpochMilli(ms);
    }

    @SafeVarargs
    private static <T> T firstNonNull(T ... values) {
        for (T v : values) {
            if (v == null) continue;
            return v;
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    public record TrtcCallbackResponse(Integer ErrorCode, String ErrorMessage, String ActionStatus, String ErrorInfo) {
        static TrtcCallbackResponse legacyOk() {
            return new TrtcCallbackResponse(0, "Success", null, null);
        }

        static TrtcCallbackResponse legacyError(int code, String message) {
            return new TrtcCallbackResponse(code, message, null, null);
        }

        static TrtcCallbackResponse v2Ok() {
            return new TrtcCallbackResponse(0, null, "OK", "");
        }

        static TrtcCallbackResponse v2Error(int code, String info) {
            return new TrtcCallbackResponse(code, null, "FAIL", info == null ? "" : info);
        }
    }
}
