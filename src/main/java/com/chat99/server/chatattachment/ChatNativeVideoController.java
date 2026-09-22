package com.chat99.server.chatattachment;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatNativeVideoController {

    private final ChatNativeVideoService nativeVideoService;
    private final ChatAttachmentRateLimiter rateLimiter;
    private final ChatAttachmentCapabilityService capabilityService;

    public ChatNativeVideoController(ChatNativeVideoService nativeVideoService,
                                     ChatAttachmentRateLimiter rateLimiter,
                                     ChatAttachmentCapabilityService capabilityService) {
        this.nativeVideoService = nativeVideoService;
        this.rateLimiter = rateLimiter;
        this.capabilityService = capabilityService;
    }

    public record SendBody(
        String clientOperationId,
        String attachmentId,
        String referenceId,
        String conversationType,
        String peerUserId,
        String groupId,
        @JsonDeserialize(using = ChatLenientNumberJson.LongDeserializer.class)
        Long durationMs
    ) {}

    @PostMapping("/me/chat/native-video-messages")
    public Map<String, Object> send(Authentication auth,
                                    HttpServletRequest request,
                                    @RequestBody SendBody body) {
        String userId = (String) auth.getPrincipal();
        rateLimiter.checkComplete(userId);
        boolean capable = capabilityService.readAndStore(userId, request).capable();
        nativeVideoService.requireEnabled(capable);
        String mediaBaseUrl = nativeVideoService.resolveMediaBaseUrl(request);
        var req = new ChatNativeVideoService.SendRequest(
            body == null ? null : body.clientOperationId(),
            body == null ? null : body.attachmentId(),
            body == null ? null : body.referenceId(),
            body == null ? null : body.conversationType(),
            body == null ? null : body.peerUserId(),
            body == null ? null : body.groupId(),
            body == null ? null : body.durationMs());
        ChatNativeVideoService.Prep prep = nativeVideoService.prepare(userId, req, mediaBaseUrl);
        if (prep.deliverNow()) {
            nativeVideoService.deliver(prep.row().getOperationId());
        }
        return nativeVideoService.viewByOperationId(prep.row().getOperationId());
    }

    @GetMapping("/me/chat/native-video-messages/{clientOperationId:.+}")
    public Map<String, Object> get(Authentication auth, @PathVariable String clientOperationId) {
        String userId = (String) auth.getPrincipal();
        rateLimiter.checkStatus(userId);
        return nativeVideoService.get(userId, clientOperationId);
    }
}
