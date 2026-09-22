package com.chat99.server.chatattachment;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatAttachmentController {

    private final ChatAttachmentQueryService queryService;
    private final ChatAttachmentReferenceService referenceService;
    private final ChatAttachmentAccessService accessService;
    private final ChatAttachmentRateLimiter rateLimiter;
    private final ChatAttachmentCapabilityService capabilityService;

    public ChatAttachmentController(ChatAttachmentQueryService queryService,
                                    ChatAttachmentReferenceService referenceService,
                                    ChatAttachmentAccessService accessService,
                                    ChatAttachmentRateLimiter rateLimiter,
                                    ChatAttachmentCapabilityService capabilityService) {
        this.queryService = queryService;
        this.referenceService = referenceService;
        this.accessService = accessService;
        this.rateLimiter = rateLimiter;
        this.capabilityService = capabilityService;
    }

    @GetMapping("/me/chat/attachments/{attachmentId}")
    public Map<String, Object> get(Authentication auth, @PathVariable String attachmentId) {
        return queryService.get((String) auth.getPrincipal(), attachmentId);
    }

    @PostMapping("/me/chat/attachments/{attachmentId}/references")
    public Map<String, Object> references(Authentication auth,
                                          HttpServletRequest request,
                                          @PathVariable String attachmentId,
                                          @RequestBody ChatAttachmentReferenceService.CreateRequest body) {
        String userId = (String) auth.getPrincipal();
        boolean capable = capabilityService.readAndStore(userId, request).capable();
        return referenceService.create(userId, attachmentId, body, capable);
    }

    @PostMapping("/me/chat/attachments/{attachmentId}/access")
    public Map<String, Object> access(Authentication auth,
                                      @PathVariable String attachmentId,
                                      @RequestBody ChatAttachmentAccessService.AccessRequest body) {
        String userId = (String) auth.getPrincipal();
        rateLimiter.checkAccess(userId);
        return accessService.access(userId, attachmentId, body);
    }
}
