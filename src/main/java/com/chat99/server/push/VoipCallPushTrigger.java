package com.chat99.server.push;

import com.chat99.server.call.AvCallImSignalingParser;
import com.chat99.server.call.AvCallImSignalingParser.ParsedEvent;
import com.chat99.server.call.CallProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * TRTC/TUICallKit {@code av_call} VoIP trigger. Disabled when
 * {@code chat99.trtc.callback.enabled=false}; LiveKit uses {@link VoipPushService} directly.
 */
@Service
public class VoipCallPushTrigger {

    private static final Logger log = LoggerFactory.getLogger(VoipCallPushTrigger.class);

    private final VoipPushService voipPushService;
    private final ObjectMapper json;
    private final CallProperties callProperties;

    public VoipCallPushTrigger(VoipPushService voipPushService,
                               ObjectMapper json,
                               CallProperties callProperties) {
        this.voipPushService = voipPushService;
        this.json = json;
        this.callProperties = callProperties;
    }

    public void tryFromImBody(Map<String, Object> imBody) {
        if (!callProperties.enabled()) {
            log.debug("voip push skipped: trtc callback disabled (LiveKit only)");
            return;
        }
        AvCallImSignalingParser.tryParse(json, imBody).ifPresentOrElse(
            this::tryFromParsedEvent,
            () -> log.debug("voip push skipped: av_call parse miss"));
    }

    public void tryFromParsedEvent(ParsedEvent event) {
        if (!callProperties.enabled()) {
            return;
        }
        if (!AvCallImSignalingParser.isIncomingInvite(event)) {
            return;
        }
        voipPushService.sendIncomingCall(new VoipCallPush(
            event.inviteId(),
            event.callerId(),
            event.calleeId(),
            AvCallImSignalingParser.normalizeMediaType(event.callType()),
            event.roomId()));
    }
}
