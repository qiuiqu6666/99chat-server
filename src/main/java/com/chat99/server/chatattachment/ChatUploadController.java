package com.chat99.server.chatattachment;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatUploadController {

    private final ChatUploadService uploadService;
    private final ChatAttachmentRateLimiter rateLimiter;
    private final ChatAttachmentCapabilityService capabilityService;

    public ChatUploadController(ChatUploadService uploadService,
                                ChatAttachmentRateLimiter rateLimiter,
                                ChatAttachmentCapabilityService capabilityService) {
        this.uploadService = uploadService;
        this.rateLimiter = rateLimiter;
        this.capabilityService = capabilityService;
    }

    public record InitBody(
        String clientUploadKey,
        String conversationType,
        String peerUserId,
        String groupId,
        String kind,
        String nativeMessageKind,
        String originalName,
        String mimeType,
        Long declaredSizeBytes,
        String declaredChecksumAlgorithm,
        String declaredChecksum,
        @JsonDeserialize(using = ChatLenientNumberJson.LongDeserializer.class)
        Long durationMs,
        @JsonDeserialize(using = ChatLenientNumberJson.IntDeserializer.class)
        Integer width,
        @JsonDeserialize(using = ChatLenientNumberJson.IntDeserializer.class)
        Integer height
    ) {}

    public record PartUrlsBody(List<Integer> partNumbers) {}

    public record CompleteBody(List<ChatUploadService.PartSpec> parts) {}

    @PostMapping("/me/chat/uploads")
    public Map<String, Object> init(Authentication auth,
                                    HttpServletRequest request,
                                    @Valid @RequestBody InitBody body) {
        String userId = (String) auth.getPrincipal();
        rateLimiter.checkInit(userId);
        boolean capable = capabilityService.readAndStore(userId, request).capable();
        return uploadService.init(userId, new ChatUploadService.InitRequest(
            body.clientUploadKey(), body.conversationType(), body.peerUserId(), body.groupId(),
            body.kind(), body.nativeMessageKind(), body.originalName(), body.mimeType(),
            body.declaredSizeBytes(), body.declaredChecksumAlgorithm(), body.declaredChecksum(),
            body.durationMs(), body.width(), body.height()), capable);
    }

    @PostMapping("/me/chat/uploads/{uploadId}/part-urls")
    public Map<String, Object> partUrls(Authentication auth,
                                        HttpServletRequest request,
                                        @PathVariable String uploadId,
                                        @RequestBody PartUrlsBody body) {
        String userId = (String) auth.getPrincipal();
        rateLimiter.checkPartUrls(userId);
        boolean capable = capabilityService.readAndStore(userId, request).capable();
        return uploadService.partUrls(userId, uploadId,
            new ChatUploadService.PartUrlRequest(body == null ? List.of() : body.partNumbers()), capable);
    }

    @GetMapping("/me/chat/uploads/{uploadId}")
    public Map<String, Object> get(Authentication auth,
                                   @PathVariable String uploadId,
                                   @RequestParam(required = false) Integer afterPartNumber,
                                   @RequestParam(defaultValue = "100") int limit) {
        String userId = (String) auth.getPrincipal();
        rateLimiter.checkStatus(userId);
        return uploadService.get(userId, uploadId, afterPartNumber, limit);
    }

    @PostMapping("/me/chat/uploads/{uploadId}/complete")
    public Map<String, Object> complete(Authentication auth,
                                        HttpServletRequest request,
                                        @PathVariable String uploadId,
                                        @RequestBody(required = false) CompleteBody body) {
        String userId = (String) auth.getPrincipal();
        rateLimiter.checkComplete(userId);
        boolean capable = capabilityService.readAndStore(userId, request).capable();
        return uploadService.complete(userId, uploadId,
            new ChatUploadService.CompleteRequest(body == null ? null : body.parts()), capable);
    }

    @DeleteMapping("/me/chat/uploads/{uploadId}")
    public Map<String, Object> cancel(Authentication auth, @PathVariable String uploadId) {
        return uploadService.cancel((String) auth.getPrincipal(), uploadId);
    }

    @PostMapping("/me/chat/uploads/{uploadId}/thumbnail-upload")
    public Map<String, Object> thumbnailUpload(Authentication auth,
                                               HttpServletRequest request,
                                               @PathVariable String uploadId) {
        String userId = (String) auth.getPrincipal();
        rateLimiter.checkThumbnail(userId);
        boolean capable = capabilityService.readAndStore(userId, request).capable();
        return uploadService.initThumbnail(userId, uploadId, capable);
    }

    @PostMapping("/me/chat/uploads/{uploadId}/thumbnail-complete")
    public Map<String, Object> thumbnailComplete(Authentication auth,
                                                 HttpServletRequest request,
                                                 @PathVariable String uploadId) {
        String userId = (String) auth.getPrincipal();
        rateLimiter.checkComplete(userId);
        boolean capable = capabilityService.readAndStore(userId, request).capable();
        return uploadService.completeThumbnail(userId, uploadId, capable);
    }
}
