package com.chat99.server.chatattachment;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatAttachmentPolicyController {

    private final ChatAttachmentProperties props;
    private final ChatAttachmentOssClient ossClient;
    private final ChatAttachmentCapabilityService capabilityService;

    public ChatAttachmentPolicyController(ChatAttachmentProperties props,
                                          ChatAttachmentOssClient ossClient,
                                          ChatAttachmentCapabilityService capabilityService) {
        this.props = props;
        this.ossClient = ossClient;
        this.capabilityService = capabilityService;
    }

    @GetMapping("/me/chat/attachment-policy")
    public Map<String, Object> policy(Authentication auth, HttpServletRequest request) {
        String userId = (String) auth.getPrincipal();
        var cap = capabilityService.readAndStore(userId, request);
        boolean ossReady = ossClient.isReady();
        boolean upload = !props.emergencyDisabled() && ossReady && props.uploadEnabled() && cap.capable();
        boolean send = !props.emergencyDisabled() && ossReady && props.sendEnabled() && cap.capable()
            && props.gray().senderAllowed(userId);
        boolean read = !props.emergencyDisabled() && props.readEnabled();
        boolean nativeVideo = !props.emergencyDisabled() && ossReady && props.uploadEnabled()
            && props.sendEnabled() && props.nativeVideoMessageEnabled() && cap.capable()
            && props.gray().senderAllowed(userId);

        Map<String, Object> nativeMax = new LinkedHashMap<>();
        nativeMax.put("image", props.nativeMaxBytes().image());
        nativeMax.put("sound", props.nativeMaxBytes().sound());
        nativeMax.put("video", props.nativeMaxBytes().video());
        nativeMax.put("file", props.nativeMaxBytes().file());

        Map<String, Object> thumbnail = new LinkedHashMap<>();
        thumbnail.put("maxBytes", props.thumbnailMaxBytes());
        thumbnail.put("maxLongEdge", props.thumbnailMaxLongEdge());
        thumbnail.put("mimeType", "image/jpeg");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("uploadEnabled", upload);
        out.put("sendEnabled", send);
        out.put("readEnabled", read);
        out.put("nativeVideoMessageEnabled", nativeVideo);
        out.put("policyVersion", props.policyVersion());
        out.put("routingThresholdBytes", props.routingThresholdBytes());
        out.put("sizeComparison", "strictGreaterThan");
        out.put("nativeMaxBytes", nativeMax);
        out.put("maxAttachmentBytes", props.maxAttachmentBytes());
        out.put("userStorageQuotaBytes", props.userStorageQuotaBytes());
        out.put("dailyUploadQuotaBytes", props.dailyUploadQuotaBytes());
        out.put("quotaDayTimezone", props.quotaDayTimezone());
        out.put("maxActiveUploadsPerUser", props.maxActiveUploadsPerUser());
        out.put("partSizeBytes", props.partSizeBytes());
        out.put("maxParallelPartsPerUpload", props.maxParallelPartsPerUpload());
        out.put("uploadSessionTtlSeconds", props.uploadSessionTtlSeconds());
        out.put("uploadPartUrlTtlSeconds", props.uploadPartUrlTtlSeconds());
        out.put("accessUrlTtlSeconds", props.accessUrlTtlSeconds());
        out.put("confirmedRetentionDays", props.confirmedRetentionDays());
        out.put("supportedPlatforms", props.supportedPlatforms());
        out.put("minReceiverVersion", props.minReceiverVersion());
        out.put("minReceiverBuild", props.minReceiverBuild());
        out.put("requiredProtocolVersion", props.requiredProtocolVersion());
        out.put("thumbnail", thumbnail);
        out.put("callerCapable", cap.capable());
        return out;
    }
}
