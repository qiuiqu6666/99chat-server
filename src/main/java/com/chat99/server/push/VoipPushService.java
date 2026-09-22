package com.chat99.server.push;

import com.chat99.server.call.CallUserIdNormalizer;
import com.chat99.server.im.ImPushDedupStore;
import com.chat99.server.user.UserNotificationSettingsService;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class VoipPushService {

    private static final Logger log = LoggerFactory.getLogger(VoipPushService.class);

    private final PushConfigService pushConfig;
    private final UserPushTokenRepository tokenRepository;
    private final PushTokenService pushTokenService;
    private final ApnsVoipPushSender voipPushSender;
    private final PushAvatarResolver pushAvatarResolver;
    private final PushDisplayNameResolver pushDisplayNameResolver;
    private final ImPushDedupStore dedupStore;
    private final UserNotificationSettingsService notificationSettings;

    public VoipPushService(PushConfigService pushConfig,
                           UserPushTokenRepository tokenRepository,
                           PushTokenService pushTokenService,
                           ApnsVoipPushSender voipPushSender,
                           PushAvatarResolver pushAvatarResolver,
                           PushDisplayNameResolver pushDisplayNameResolver,
                           ImPushDedupStore dedupStore,
                           UserNotificationSettingsService notificationSettings) {
        this.pushConfig = pushConfig;
        this.tokenRepository = tokenRepository;
        this.pushTokenService = pushTokenService;
        this.voipPushSender = voipPushSender;
        this.pushAvatarResolver = pushAvatarResolver;
        this.pushDisplayNameResolver = pushDisplayNameResolver;
        this.dedupStore = dedupStore;
        this.notificationSettings = notificationSettings;
    }

    public boolean enabled() {
        return pushConfig.isPushEnabled()
            && pushConfig.isVoipPushEnabled()
            && voipPushSender.isReady();
    }

    public void sendIncomingCall(VoipCallPush call) {
        if (!enabled() || call == null) {
            return;
        }
        if (call.calleeId() == null || call.calleeId().isBlank()
            || call.inviteId() == null || call.inviteId().isBlank()) {
            return;
        }
        String rawCallerId = call.callerId();
        String rawCalleeId = call.calleeId();
        String callerId = CallUserIdNormalizer.normalize(rawCallerId);
        String calleeId = CallUserIdNormalizer.normalize(rawCalleeId);
        if (CallUserIdNormalizer.wasCompositeId(rawCallerId, callerId)) {
            log.warn("voip push composite callerId normalized raw={} normalized={} inviteId={}",
                rawCallerId, callerId, call.inviteId());
        }
        if (CallUserIdNormalizer.wasCompositeId(rawCalleeId, calleeId)) {
            log.warn("voip push composite calleeId normalized raw={} normalized={} inviteId={}",
                rawCalleeId, calleeId, call.inviteId());
        }
        if (calleeId.isBlank()) {
            return;
        }
        call = new VoipCallPush(
            call.inviteId(), callerId, calleeId,
            call.mediaType(), call.roomId(),
            call.callerName(), call.callerAvatarUrl(), call.type(), call.action());
        if (!notificationSettings.isCallNotificationEnabled(call.calleeId())) {
            log.debug("voip push skipped: call notification disabled callee={}", call.calleeId());
            return;
        }
        if (call.isTerminal()) {
            notifyAllCalleeDevices(call);
            return;
        }
        String dedupKey = "voip|" + call.inviteId() + "|" + call.calleeId();
        if (!dedupStore.markIfNew(dedupKey)) {
            log.debug("voip push duplicate inviteId={} callee={}", call.inviteId(), call.calleeId());
            return;
        }
        // PushKit 状态与普通 APNs 独立，也不因在线心跳跳过。
        // 同一 userId 多台 VoIP 同时推会导致多机抢同一 LiveKit identity（DUPLICATE_IDENTITY）。
        // 按活跃度从新到旧单台尝试；格式非法或 APNs 判脏 token 立即 clear，再试下一台。
        List<UserPushToken> voipTokens = tokenRepository.findByUserIdAndVoipEnabledTrue(call.calleeId()).stream()
            .filter(t -> t.getVoipPushToken() != null && !t.getVoipPushToken().isBlank())
            .sorted(Comparator
                .comparing(VoipPushService::activityAt)
                .thenComparing(t -> t.getId() == null ? 0L : t.getId())
                .reversed())
            .collect(Collectors.toList());
        if (voipTokens.isEmpty()) {
            log.info("voip push skipped: no active voip token callee={}", call.calleeId());
            return;
        }
        VoipCallPush enriched = enrichCall(call);
        int attempted = 0;
        for (UserPushToken candidate : voipTokens) {
            if (!isPlausibleVoipToken(candidate.getVoipPushToken())) {
                pushTokenService.clearVoipToken(candidate.getId());
                log.warn("voip push cleared implausible token callee={} deviceId={} inviteId={} len={}",
                    enriched.calleeId(), candidate.getDeviceId(), enriched.inviteId(),
                    candidate.getVoipPushToken() == null ? 0 : candidate.getVoipPushToken().trim().length());
                continue;
            }
            attempted++;
            if (attempted == 1 && voipTokens.size() > 1) {
                String skipped = voipTokens.stream()
                    .filter(t -> !Objects.equals(t.getId(), candidate.getId()))
                    .map(UserPushToken::getDeviceId)
                    .collect(Collectors.joining(","));
                log.info(
                    "voip push prefer latest device callee={} selected={} skippedDevices={} candidates={}",
                    call.calleeId(), candidate.getDeviceId(), skipped, voipTokens.size());
            }
            PushSendResult result = voipPushSender.send(candidate, enriched);
            if (result.invalidToken()) {
                pushTokenService.clearVoipToken(candidate.getId());
                log.warn("voip push invalid token cleared callee={} deviceId={} inviteId={} detail={}",
                    enriched.calleeId(), candidate.getDeviceId(), enriched.inviteId(), result.detail());
                continue;
            }
            if (result.sent()) {
                log.info("voip push sent callee={} caller={} ({}) inviteId={} devices=1 deviceId={}",
                    enriched.calleeId(), enriched.callerId(), enriched.callerName(),
                    enriched.inviteId(), candidate.getDeviceId());
                return;
            }
            log.warn("voip push failed callee={} inviteId={} deviceId={} detail={}",
                enriched.calleeId(), enriched.inviteId(), candidate.getDeviceId(), result.detail());
            return;
        }
        if (attempted == 0) {
            log.info("voip push skipped: no plausible voip token callee={}", call.calleeId());
        } else {
            log.warn("voip push failed all devices callee={} inviteId={} candidates={}",
                call.calleeId(), call.inviteId(), attempted);
        }
    }

    /**
     * 被叫多端停铃：App 内靠 IM；系统 CallKit 靠本方法向<strong>全部</strong> voip_enabled 设备再推终态。
     * 与来电不同：不限「最新一台」，不因通话通知开关跳过（可能已在响铃）。
     */
    public void notifyCalleeDevicesEnded(String inviteId, String callerId, String calleeId,
                                         String mediaType, String roomId, String type, String action) {
        if (!enabled() || inviteId == null || inviteId.isBlank()
            || calleeId == null || calleeId.isBlank()) {
            return;
        }
        String normalizedCaller = CallUserIdNormalizer.normalize(callerId);
        String normalizedCallee = CallUserIdNormalizer.normalize(calleeId);
        VoipCallPush ended = VoipCallPush.ended(
            inviteId, normalizedCaller, normalizedCallee, mediaType, roomId,
            type == null || type.isBlank() ? "lk_call" : type,
            action == null || action.isBlank() ? "answered_elsewhere" : action);
        notifyAllCalleeDevices(ended);
    }

    private void notifyAllCalleeDevices(VoipCallPush call) {
        String dedupKey = "voip|end|" + call.action() + "|" + call.inviteId() + "|" + call.calleeId();
        if (!dedupStore.markIfNew(dedupKey)) {
            log.debug("voip end push duplicate action={} inviteId={} callee={}",
                call.action(), call.inviteId(), call.calleeId());
            return;
        }
        List<UserPushToken> voipTokens = tokenRepository.findByUserIdAndVoipEnabledTrue(call.calleeId()).stream()
            .filter(t -> t.getVoipPushToken() != null && !t.getVoipPushToken().isBlank())
            .collect(Collectors.toList());
        if (voipTokens.isEmpty()) {
            log.info("voip end push skipped: no voip token callee={} action={}",
                call.calleeId(), call.action());
            return;
        }
        VoipCallPush enriched = enrichCall(call);
        int sent = 0;
        for (UserPushToken candidate : voipTokens) {
            if (!isPlausibleVoipToken(candidate.getVoipPushToken())) {
                pushTokenService.clearVoipToken(candidate.getId());
                continue;
            }
            PushSendResult result = voipPushSender.send(candidate, enriched);
            if (result.invalidToken()) {
                pushTokenService.clearVoipToken(candidate.getId());
                log.warn("voip end push invalid token cleared callee={} deviceId={} action={}",
                    enriched.calleeId(), candidate.getDeviceId(), enriched.action());
                continue;
            }
            if (result.sent()) {
                sent++;
            } else {
                log.warn("voip end push failed callee={} deviceId={} action={} detail={}",
                    enriched.calleeId(), candidate.getDeviceId(), enriched.action(), result.detail());
            }
        }
        log.info("voip end push done callee={} inviteId={} action={} sent={} candidates={}",
            enriched.calleeId(), enriched.inviteId(), enriched.action(), sent, voipTokens.size());
    }

    /**
     * APNs device token 一般为 64～200 位十六进制；明显非法的直接清掉，避免反复打 APNs。
     */
    static boolean isPlausibleVoipToken(String token) {
        if (token == null) {
            return false;
        }
        String t = token.trim();
        int len = t.length();
        if (len < 64 || len > 200) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            char c = t.charAt(i);
            boolean hex = (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'f')
                || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /**
     * Prefer the VoIP device with the newest activity signal.
     * Order: lastSeenAt → updatedAt → createdAt → id.
     */
    static UserPushToken selectPreferredVoipDevice(List<UserPushToken> voipTokens) {
        return voipTokens.stream()
            .max(Comparator
                .comparing(VoipPushService::activityAt)
                .thenComparing(t -> t.getId() == null ? 0L : t.getId()))
            .orElseThrow();
    }

    private static Instant activityAt(UserPushToken token) {
        if (token.getLastSeenAt() != null) {
            return token.getLastSeenAt();
        }
        if (token.getUpdatedAt() != null) {
            return token.getUpdatedAt();
        }
        if (token.getCreatedAt() != null) {
            return token.getCreatedAt();
        }
        return Instant.EPOCH;
    }

    private VoipCallPush enrichCall(VoipCallPush call) {
        String callerId = call.callerId();
        String calleeId = call.calleeId();
        String callerName = call.callerName();
        if (callerName == null || callerName.isBlank()) {
            callerName = pushDisplayNameResolver.resolveCallerDisplayName(calleeId, callerId);
        }
        if (!CallUserIdNormalizer.isValidVoipDisplayName(callerName, callerId)) {
            callerName = callerId;
        }
        String callerAvatarUrl = call.callerAvatarUrl();
        if (callerAvatarUrl == null || callerAvatarUrl.isBlank()) {
            callerAvatarUrl = pushAvatarResolver.resolveUserAvatarUrl(call.callerId());
        }
        return new VoipCallPush(
            call.inviteId(), call.callerId(), call.calleeId(),
            call.mediaType(), call.roomId(), callerName, callerAvatarUrl, call.type(), call.action());
    }
}
