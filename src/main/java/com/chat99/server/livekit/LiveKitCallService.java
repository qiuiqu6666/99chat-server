package com.chat99.server.livekit;

import com.chat99.server.call.CallRecordResult;
import com.chat99.server.call.CallRecordUser;
import com.chat99.server.call.CallRecordUserRepository;
import com.chat99.server.call.CallSession;
import com.chat99.server.call.CallSessionRepository;
import com.chat99.server.call.CallSessionStatus;
import com.chat99.server.call.TrtcCallResultMapper;
import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImUserIdService;
import com.chat99.server.push.VoipCallPush;
import com.chat99.server.push.VoipPushService;
import com.chat99.server.realtime.CallRecentRealtimePublisher;
import com.chat99.server.user.UserFriendService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LiveKitCallService {

    private static final Logger log = LoggerFactory.getLogger(LiveKitCallService.class);
    public static final String CALL_TYPE = "livekit";
    public static final String SIGNAL_BUSINESS_ID = "lk_call";
    public static final String PUSH_TYPE = "lk_call";

    private final LiveKitProperties props;
    private final LiveKitAccessTokenService tokenService;
    private final CallSessionRepository sessionRepository;
    private final CallRecordUserRepository recordRepository;
    private final UserFriendService friendService;
    private final ImAdminClient imAdmin;
    private final ImUserIdService imUserIdService;
    private final VoipPushService voipPushService;
    private final CallRecentRealtimePublisher callRecentRealtimePublisher;

    public LiveKitCallService(LiveKitProperties props,
                              LiveKitAccessTokenService tokenService,
                              CallSessionRepository sessionRepository,
                              CallRecordUserRepository recordRepository,
                              UserFriendService friendService,
                              ImAdminClient imAdmin,
                              ImUserIdService imUserIdService,
                              VoipPushService voipPushService,
                              CallRecentRealtimePublisher callRecentRealtimePublisher) {
        this.props = props;
        this.tokenService = tokenService;
        this.sessionRepository = sessionRepository;
        this.recordRepository = recordRepository;
        this.friendService = friendService;
        this.imAdmin = imAdmin;
        this.imUserIdService = imUserIdService;
        this.voipPushService = voipPushService;
        this.callRecentRealtimePublisher = callRecentRealtimePublisher;
    }

    @Transactional
    public CallCreds invite(String callerId, InviteRequest req) {
        long t0 = System.nanoTime();
        tokenService.requireConfigured();
        // 客户端可能传业务号或 IM 数字号；会话/好友/VoIP 一律落业务号
        String calleeId = toBusinessUserId(requirePeer(req == null ? null : req.calleeUserId()));
        if (callerId.equals(calleeId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CANNOT_CALL_SELF");
        }
        long tFriend = System.nanoTime();
        requireCanCall(callerId, calleeId);
        long friendMs = elapsedMs(tFriend);

        // 取消/挂断未落库或 webhook 刷新 lastEventAt 时，脏 RINGING 会导致误报忙线
        long tRing = System.nanoTime();
        releaseAbandonedRinging(callerId, calleeId);
        if (hasOpenRingingAsCallee(calleeId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "CALLEE_BUSY");
        }
        long ringMs = elapsedMs(tRing);

        String mediaType = TrtcCallResultMapper.normalizeMediaType(req == null ? null : req.mediaType());
        String callId = UUID.randomUUID().toString().replace("-", "");
        String roomName = "call_" + callId;
        Instant now = Instant.now();

        long tDb = System.nanoTime();
        CallSession session = new CallSession();
        session.setCallId(callId);
        session.setRoomId(roomName);
        session.setCallType(CALL_TYPE);
        session.setMediaType(mediaType);
        session.setCallerUserId(callerId);
        session.setCalleeUserId(calleeId);
        session.setStartedAt(now);
        session.setLastEventAt(now);
        session.setStatus(CallSessionStatus.RINGING);
        sessionRepository.save(session);

        upsertUserRecord(callId, callerId, calleeId, "caller", "outgoing", CallRecordResult.MISSED, 0, now);
        upsertUserRecord(callId, calleeId, callerId, "callee", "incoming", CallRecordResult.MISSED, 0, now);
        callRecentRealtimePublisher.callEnded(callId, callerId, calleeId);
        long dbMs = elapsedMs(tDb);

        long tIm = System.nanoTime();
        sendSignal(callerId, calleeId, "invite", callId, roomName, mediaType, callerId, calleeId);
        long imMs = elapsedMs(tIm);

        long tVoip = System.nanoTime();
        voipPushService.sendIncomingCall(new VoipCallPush(
            callId, callerId, calleeId, mediaType, roomName, null, null, PUSH_TYPE));
        long voipMs = elapsedMs(tVoip);

        long tToken = System.nanoTime();
        LiveKitAccessTokenService.IssuedToken issued = tokenService.issueRoomToken(callerId, roomName);
        long tokenMs = elapsedMs(tToken);

        log.info(
            "livekit invite callId={} caller={} callee={} media={} friendMs={} ringMs={} dbMs={} imMs={} voipMs={} tokenMs={} totalMs={}",
            callId, callerId, calleeId, mediaType,
            friendMs, ringMs, dbMs, imMs, voipMs, tokenMs, elapsedMs(t0));
        return toCreds(session, issued);
    }

    @Transactional
    public CallCreds accept(String userId, String callId) {
        tokenService.requireConfigured();
        CallSession session = requireOpenSession(callId);
        if (!userId.equals(session.getCalleeUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_CALLEE");
        }
        if (session.getStatus() != CallSessionStatus.RINGING && session.getAcceptedAt() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "CALL_NOT_RINGING");
        }
        if (session.getAcceptedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "CALL_ALREADY_ANSWERED");
        }
        Instant now = Instant.now();
        session.setAcceptedAt(now);
        session.setStatus(CallSessionStatus.ANSWERED);
        session.setLastEventAt(now);
        sessionRepository.save(session);
        upsertUserRecord(callId, session.getCallerUserId(), session.getCalleeUserId(),
            "caller", "outgoing", CallRecordResult.ANSWERED, 0, now);
        upsertUserRecord(callId, session.getCalleeUserId(), session.getCallerUserId(),
            "callee", "incoming", CallRecordResult.ANSWERED, 0, now);
        callRecentRealtimePublisher.callEnded(callId, session.getCallerUserId(), session.getCalleeUserId());
        sendSignal(userId, session.getCallerUserId(), "accept", callId, session.getRoomId(),
            session.getMediaType(), session.getCallerUserId(), session.getCalleeUserId());
        // App 内其它端：IM answered_elsewhere → 被叫所有在线设备
        // 系统 CallKit：VoIP 终态推到全部 voip token
        stopCalleeOtherEndpoints(session, "answered_elsewhere");
        LiveKitAccessTokenService.IssuedToken issued =
            tokenService.issueRoomToken(userId, session.getRoomId());
        return toCreds(session, issued);
    }

    @Transactional
    public Map<String, Object> reject(String userId, String callId) {
        CallSession session = requireOpenSession(callId);
        if (!userId.equals(session.getCalleeUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_CALLEE");
        }
        finalize(session, CallRecordResult.REJECTED, Instant.now());
        sendSignal(userId, session.getCallerUserId(), "reject", callId, session.getRoomId(),
            session.getMediaType(), session.getCallerUserId(), session.getCalleeUserId());
        stopCalleeOtherEndpoints(session, "reject");
        return Map.of("ok", true, "callId", callId, "result", "REJECTED");
    }

    @Transactional
    public Map<String, Object> cancel(String userId, String callId) {
        long t0 = System.nanoTime();
        long tLoad = System.nanoTime();
        CallSession session = requireOpenSession(callId);
        long loadMs = elapsedMs(tLoad);
        if (!userId.equals(session.getCallerUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_CALLER");
        }
        if (session.getAcceptedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "CALL_ALREADY_ANSWERED");
        }
        long tDb = System.nanoTime();
        finalize(session, CallRecordResult.CANCELED, Instant.now());
        long dbMs = elapsedMs(tDb);
        long tIm = System.nanoTime();
        sendSignal(userId, session.getCalleeUserId(), "cancel", callId, session.getRoomId(),
            session.getMediaType(), session.getCallerUserId(), session.getCalleeUserId());
        long imMs = elapsedMs(tIm);
        log.info("livekit cancel callId={} userId={} loadMs={} dbMs={} imMs={} totalMs={}",
            callId, userId, loadMs, dbMs, imMs, elapsedMs(t0));
        return Map.of("ok", true, "callId", callId, "result", "CANCELED");
    }

    @Transactional
    public Map<String, Object> hangup(String userId, String callId) {
        CallSession session = requireSession(callId);
        if (!userId.equals(session.getCallerUserId()) && !userId.equals(session.getCalleeUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_PARTICIPANT");
        }
        if (session.getEndedAt() != null) {
            return Map.of("ok", true, "callId", callId, "result", session.getStatus().name());
        }
        boolean wasRinging = session.getAcceptedAt() == null;
        CallRecordResult result = session.getAcceptedAt() != null
            ? CallRecordResult.ANSWERED
            : (userId.equals(session.getCallerUserId())
                ? CallRecordResult.CANCELED
                : CallRecordResult.REJECTED);
        finalize(session, result, Instant.now());
        String peer = userId.equals(session.getCallerUserId())
            ? session.getCalleeUserId()
            : session.getCallerUserId();
        sendSignal(userId, peer, "hangup", callId, session.getRoomId(),
            session.getMediaType(), session.getCallerUserId(), session.getCalleeUserId());
        if (!wasRinging || userId.equals(session.getCallerUserId())) {
            sendSignal(peer, userId, "hangup", callId, session.getRoomId(),
                session.getMediaType(), session.getCallerUserId(), session.getCalleeUserId());
        }
        if (wasRinging) {
            stopCalleeOtherEndpoints(session, "hangup");
        }
        return Map.of("ok", true, "callId", callId, "result", result.name());
    }

    @Transactional(readOnly = true)
    public CallCreds token(String userId, String callId) {
        tokenService.requireConfigured();
        CallSession session = requireSession(callId);
        if (!userId.equals(session.getCallerUserId()) && !userId.equals(session.getCalleeUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_PARTICIPANT");
        }
        if (session.getEndedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "CALL_ENDED");
        }
        LiveKitAccessTokenService.IssuedToken issued =
            tokenService.issueRoomToken(userId, session.getRoomId());
        return toCreds(session, issued);
    }

    @Transactional
    public void handleWebhookEvent(Map<String, Object> event) {
        if (event == null || event.isEmpty()) {
            return;
        }
        String eventType = str(event.get("event"));
        if (eventType == null) {
            return;
        }
        String roomName = roomNameFrom(event);
        if (roomName == null || !roomName.startsWith("call_")) {
            return;
        }
        String callId = roomName.substring("call_".length());
        Optional<CallSession> opt = sessionRepository.findById(callId);
        if (opt.isEmpty()) {
            return;
        }
        CallSession session = opt.get();
        if (session.getCallType() == null || !CALL_TYPE.equalsIgnoreCase(session.getCallType())) {
            return;
        }
        Instant now = Instant.now();
        switch (eventType) {
            case "participant_joined" -> {
                String identity = participantIdentity(event);
                if (identity != null && identity.equals(session.getCalleeUserId())
                    && session.getAcceptedAt() == null && session.getEndedAt() == null) {
                    session.setAcceptedAt(now);
                    session.setStatus(CallSessionStatus.ANSWERED);
                    session.setLastEventAt(now);
                    sessionRepository.save(session);
                    upsertUserRecord(callId, session.getCallerUserId(), session.getCalleeUserId(),
                        "caller", "outgoing", CallRecordResult.ANSWERED, 0, now);
                    upsertUserRecord(callId, session.getCalleeUserId(), session.getCallerUserId(),
                        "callee", "incoming", CallRecordResult.ANSWERED, 0, now);
                    callRecentRealtimePublisher.callEnded(callId, session.getCallerUserId(), session.getCalleeUserId());
                } else {
                    session.setLastEventAt(now);
                    sessionRepository.save(session);
                }
            }
            case "participant_left", "track_unpublished" -> {
                // 未接听阶段不刷新 lastEventAt，避免拖延振铃超时清理
                if (session.getAcceptedAt() != null && session.getEndedAt() == null) {
                    session.setLastEventAt(now);
                    sessionRepository.save(session);
                }
            }
            case "track_published" -> {
                if (session.getAcceptedAt() != null && session.getEndedAt() == null) {
                    session.setLastEventAt(now);
                    sessionRepository.save(session);
                    log.debug("livekit track_published callId={} identity={}",
                        callId, participantIdentity(event));
                }
            }
            case "room_finished" -> {
                if (session.getEndedAt() == null) {
                    CallRecordResult result = session.getAcceptedAt() != null
                        ? CallRecordResult.ANSWERED
                        : CallRecordResult.MISSED;
                    finalize(session, result, now);
                }
            }
            default -> log.debug("livekit webhook ignored event={}", eventType);
        }
    }

    private void finalize(CallSession session, CallRecordResult result, Instant endedAt) {
        if (session == null || session.getCallId() == null) {
            return;
        }
        Instant occurredAt = endedAt != null ? endedAt : Instant.now();
        CallSessionStatus status = TrtcCallResultMapper.toSessionStatus(result);
        // 原子 CAS：hangup 与 room_finished 并发时只有赢家写库并打日志，避免双 finalize / 时长膨胀
        int won = sessionRepository.markEndedIfOpen(session.getCallId(), occurredAt, status);
        CallSession current = sessionRepository.findById(session.getCallId()).orElse(session);
        session.setEndedAt(current.getEndedAt());
        session.setLastEventAt(current.getLastEventAt());
        session.setStatus(current.getStatus());
        if (won == 0) {
            log.debug("livekit finalize skipped (already ended) callId={} result={}",
                session.getCallId(), result);
            return;
        }
        int durationSec = durationSec(current, result, occurredAt);
        upsertUserRecord(current.getCallId(), current.getCallerUserId(), current.getCalleeUserId(),
            "caller", "outgoing", result, durationSec, occurredAt);
        upsertUserRecord(current.getCallId(), current.getCalleeUserId(), current.getCallerUserId(),
            "callee", "incoming", result, durationSec, occurredAt);
        callRecentRealtimePublisher.callEnded(
            current.getCallId(), current.getCallerUserId(), current.getCalleeUserId());
        log.info("livekit call finalized callId={} result={} durationSec={}",
            current.getCallId(), result, durationSec);
    }

    /**
     * 再次拨打前清理：主叫残留振铃直接取消；被叫侧超时未接的振铃记 MISSED。
     * 其它主叫仍在振铃窗口内的来电仍视为忙线。
     */
    private void releaseAbandonedRinging(String callerId, String calleeId) {
        Instant now = Instant.now();
        int ringSec = Math.max(props.ringTimeoutSeconds(), 1);
        Instant staleBefore = now.minusSeconds(ringSec);
        for (CallSession open : sessionRepository.findOpenRingingForCaller(callerId)) {
            if (open.getEndedAt() != null) {
                continue;
            }
            finalize(open, CallRecordResult.CANCELED, now);
            log.info("livekit released caller abandoned ringing callId={} peer={}",
                open.getCallId(), open.getCalleeUserId());
        }
        for (CallSession open : sessionRepository.findOpenRingingForCallee(calleeId)) {
            if (open.getEndedAt() != null) {
                continue;
            }
            if (callerId.equals(open.getCallerUserId())) {
                finalize(open, CallRecordResult.CANCELED, now);
                log.info("livekit released same-pair ringing callId={}", open.getCallId());
                continue;
            }
            Instant started = open.getStartedAt();
            if (started != null && started.isBefore(staleBefore)) {
                finalize(open, CallRecordResult.MISSED, now);
                log.info("livekit released stale callee ringing callId={} caller={}",
                    open.getCallId(), open.getCallerUserId());
            }
        }
    }

    private static int durationSec(CallSession session, CallRecordResult result, Instant endedAt) {
        if (result != CallRecordResult.ANSWERED || endedAt == null) {
            return 0;
        }
        Instant start = session.getAcceptedAt() != null ? session.getAcceptedAt() : session.getStartedAt();
        if (start == null) {
            return 0;
        }
        long sec = endedAt.getEpochSecond() - start.getEpochSecond();
        return sec > 0 ? (int) sec : 0;
    }

    private void sendSignal(String from, String to, String action, String callId,
                            String roomName, String mediaType, String callerId, String calleeId) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("businessID", SIGNAL_BUSINESS_ID);
            payload.put("action", action);
            payload.put("callId", callId);
            payload.put("roomName", roomName);
            payload.put("mediaType", mediaType == null ? "audio" : mediaType);
            payload.put("callerId", callerId);
            payload.put("calleeId", calleeId);
            payload.put("timeoutSec", props.ringTimeoutSeconds());
            payload.put("phase", phaseForAction(action));
            payload.put("status", statusForAction(action));
            if (isTerminalAction(action)) {
                payload.put("endedAt", System.currentTimeMillis());
            }
            imAdmin.sendCustomC2c(
                imUserIdService.toIm(from),
                imUserIdService.toIm(to),
                payload,
                signalDesc(action, mediaType));
        } catch (Exception e) {
            log.warn("livekit im signal failed action={} callId={}: {}", action, callId, e.getMessage());
        }
    }

    /**
     * 收口被叫其它端：使用 IM 信令，禁止以 PushKit 发送终态。
     * IM 用主叫→被叫发出，保证被叫多端作为 To_Account 都能收到（SyncOtherMachine=2）。
     */
    private void stopCalleeOtherEndpoints(CallSession session, String action) {
        String callId = session.getCallId();
        String callerId = session.getCallerUserId();
        String calleeId = session.getCalleeUserId();
        sendSignal(callerId, calleeId, action, callId, session.getRoomId(),
            session.getMediaType(), callerId, calleeId);
    }

    private static String signalDesc(String action, String mediaType) {
        boolean video = "video".equalsIgnoreCase(mediaType);
        return switch (action == null ? "" : action) {
            case "invite" -> video ? "[视频通话]" : "[语音通话]";
            case "accept" -> "[已接听]";
            case "answered_elsewhere" -> "[已在其他设备接听]";
            case "reject" -> "[已拒绝]";
            case "cancel" -> "[已取消]";
            case "hangup" -> "[通话结束]";
            default -> "[通话]";
        };
    }

    private static boolean isTerminalAction(String action) {
        return switch (action == null ? "" : action) {
            case "reject", "cancel", "hangup", "answered_elsewhere" -> true;
            default -> false;
        };
    }

    private static String phaseForAction(String action) {
        if ("invite".equalsIgnoreCase(action)) return "RINGING";
        if ("accept".equalsIgnoreCase(action)) return "ANSWERED";
        return isTerminalAction(action) ? "ENDED" : "UNKNOWN";
    }

    private static String statusForAction(String action) {
        return switch (action == null ? "" : action) {
            case "invite" -> "RINGING";
            case "accept" -> "ANSWERED";
            case "reject" -> "REJECTED";
            case "cancel" -> "CANCELED";
            case "answered_elsewhere", "hangup" -> "ENDED";
            default -> "UNKNOWN";
        };
    }

    private CallCreds toCreds(CallSession session, LiveKitAccessTokenService.IssuedToken issued) {
        return new CallCreds(
            session.getCallId(),
            session.getRoomId(),
            props.host(),
            issued.token(),
            session.getMediaType(),
            issued.expiresAt().toEpochMilli(),
            session.getCallerUserId(),
            session.getCalleeUserId(),
            props.ringTimeoutSeconds());
    }

    private void requireCanCall(String callerId, String calleeId) {
        var relation = friendService.getFriendRelation(callerId, calleeId);
        if (!relation.canMessage()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_FRIENDS");
        }
    }

    private boolean hasOpenRingingAsCallee(String calleeId) {
        return sessionRepository.existsOpenRingingForCallee(calleeId);
    }

    private CallSession requireOpenSession(String callId) {
        CallSession session = requireSession(callId);
        if (session.getEndedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "CALL_ENDED");
        }
        return session;
    }

    private CallSession requireSession(String callId) {
        if (callId == null || callId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_CALL_ID");
        }
        return sessionRepository.findById(callId.trim())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "CALL_NOT_FOUND"));
    }

    private static String requirePeer(String peer) {
        if (peer == null || peer.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_CALLEE");
        }
        return peer.trim();
    }

    /** IM 号 → 业务号；已是业务号则保持。 */
    private String toBusinessUserId(String imOrBusinessUserId) {
        return imUserIdService.toBusinessForDisplay(imOrBusinessUserId);
    }

    private void upsertUserRecord(String callId, String userId, String peerUserId,
                                  String role, String direction, CallRecordResult result,
                                  int durationSec, Instant occurredAt) {
        if (callId == null || userId == null || peerUserId == null) {
            return;
        }
        CallRecordUser record = recordRepository.findByCallIdAndUserId(callId, userId).orElseGet(() -> {
            CallRecordUser r = new CallRecordUser();
            r.setCallId(callId);
            r.setUserId(userId);
            r.setDeleted(false);
            return r;
        });
        record.setPeerUserId(peerUserId);
        record.setRole(role);
        record.setDirection(direction);
        record.setResult(result);
        record.setDurationSec(durationSec);
        record.setOccurredAt(occurredAt);
        recordRepository.save(record);
    }

    @SuppressWarnings("unchecked")
    private static String roomNameFrom(Map<String, Object> event) {
        Object room = event.get("room");
        if (room instanceof Map<?, ?> map) {
            Object name = map.get("name");
            return name == null ? null : String.valueOf(name);
        }
        return str(event.get("roomName"));
    }

    @SuppressWarnings("unchecked")
    private static String participantIdentity(Map<String, Object> event) {
        Object p = event.get("participant");
        if (p instanceof Map<?, ?> map) {
            Object id = map.get("identity");
            return id == null ? null : String.valueOf(id);
        }
        return null;
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }

    public record InviteRequest(String calleeUserId, String mediaType) {}

    public record CallCreds(
        String callId,
        String roomName,
        String url,
        String token,
        String mediaType,
        long expiresAt,
        String callerUserId,
        String calleeUserId,
        int timeoutSec
    ) {}
}
