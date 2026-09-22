/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.im;

import com.chat99.server.chatattachment.ChatAttachmentImBindService;
import com.chat99.server.group.ImGroupBeforeCreateCallbackService;
import com.chat99.server.group.ImGroupBeforeJoinCallbackService;
import com.chat99.server.im.ImC2cBeforeSendMsgCallbackService;
import com.chat99.server.im.ImCallbackVerifier;
import com.chat99.server.im.ImChatPushCallbackService;
import com.chat99.server.im.ImDashboardStatsCallbackService;
import com.chat99.server.im.ImGroupBeforeSendMsgCallbackService;
import com.chat99.server.im.ImGroupMessageMonitorService;
import com.chat99.server.im.ImGroupRealtimeCallbackService;
import com.chat99.server.messagearchive.ImMessageArchiveProducer;
import com.chat99.server.messagearchive.ImMessageRecallService;
import com.chat99.server.messagearchive.MessageArchiveProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value={"/webhook/im"})
public class ImMessageWebhookController {
    private final ImCallbackVerifier callbackVerifier;
    private final ImDashboardStatsCallbackService dashboardStatsCallbackService;
    private final ImChatPushCallbackService chatPushCallbackService;
    private final ImC2cBeforeSendMsgCallbackService beforeSendMsgCallbackService;
    private final ImGroupBeforeSendMsgCallbackService groupBeforeSendMsgCallbackService;
    private final ImGroupBeforeCreateCallbackService beforeCreateGroupCallbackService;
    private final ImGroupBeforeJoinCallbackService beforeJoinGroupCallbackService;
    private final ImGroupRealtimeCallbackService groupRealtimeCallbackService;
    private final ImGroupMessageMonitorService groupMessageMonitorService;
    private final MessageArchiveProperties archiveProperties;
    private final ImMessageArchiveProducer archiveProducer;
    private final ImMessageRecallService recallService;
    private final ChatAttachmentImBindService attachmentImBindService;

    public ImMessageWebhookController(ImCallbackVerifier callbackVerifier, ImDashboardStatsCallbackService dashboardStatsCallbackService, ImChatPushCallbackService chatPushCallbackService, ImC2cBeforeSendMsgCallbackService beforeSendMsgCallbackService, ImGroupBeforeSendMsgCallbackService groupBeforeSendMsgCallbackService, ImGroupBeforeCreateCallbackService beforeCreateGroupCallbackService, ImGroupBeforeJoinCallbackService beforeJoinGroupCallbackService, ImGroupRealtimeCallbackService groupRealtimeCallbackService, ImGroupMessageMonitorService groupMessageMonitorService, MessageArchiveProperties archiveProperties, @Autowired(required=false) ImMessageArchiveProducer archiveProducer, @Autowired(required=false) ImMessageRecallService recallService, ChatAttachmentImBindService attachmentImBindService) {
        this.callbackVerifier = callbackVerifier;
        this.dashboardStatsCallbackService = dashboardStatsCallbackService;
        this.chatPushCallbackService = chatPushCallbackService;
        this.beforeSendMsgCallbackService = beforeSendMsgCallbackService;
        this.groupBeforeSendMsgCallbackService = groupBeforeSendMsgCallbackService;
        this.beforeCreateGroupCallbackService = beforeCreateGroupCallbackService;
        this.beforeJoinGroupCallbackService = beforeJoinGroupCallbackService;
        this.groupRealtimeCallbackService = groupRealtimeCallbackService;
        this.groupMessageMonitorService = groupMessageMonitorService;
        this.archiveProperties = archiveProperties;
        this.archiveProducer = archiveProducer;
        this.recallService = recallService;
        this.attachmentImBindService = attachmentImBindService;
    }

    @PostMapping(value={"/message"})
    public ImCallbackVerifier.ImCallbackResponse message(@RequestParam(value="sdkappid", required=false) String sdkAppIdLower, @RequestParam(value="SdkAppid", required=false) String sdkAppIdCamel, @RequestParam(value="command", required=false) String command, @RequestParam(value="CallbackCommand", required=false) String callbackCommand, @RequestParam(value="token", required=false) String queryToken, @RequestParam(value="Sign", required=false) String sign, @RequestParam(value="RequestTime", required=false) String requestTime, @RequestHeader(value="X-Callback-Token", required=false) String headerToken, HttpServletRequest request) throws IOException {
        String cmd;
        String body = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String token = headerToken != null && !headerToken.isBlank() ? headerToken : queryToken;
        String sdkAppId = sdkAppIdLower != null && !sdkAppIdLower.isBlank() ? sdkAppIdLower : sdkAppIdCamel;
        String string = cmd = callbackCommand != null && !callbackCommand.isBlank() ? callbackCommand : command;
        if (sign != null && !sign.isBlank()) {
            this.callbackVerifier.verifySignature(sign, requestTime);
        } else {
            this.callbackVerifier.verifyQueryToken(token);
        }
        this.dashboardStatsCallbackService.recordStats(sdkAppId, cmd, token, sign, requestTime, body);
        ImCallbackVerifier.ImCallbackResponse groupBefore = this.beforeCreateGroupCallbackService.handle(sdkAppId, cmd, token, sign, requestTime, body);
        if (groupBefore != null) {
            return groupBefore;
        }
        ImCallbackVerifier.ImCallbackResponse groupBeforeJoin = this.beforeJoinGroupCallbackService.handle(sdkAppId, cmd, token, sign, requestTime, body);
        if (groupBeforeJoin != null) {
            return groupBeforeJoin;
        }
        this.groupRealtimeCallbackService.handle(sdkAppId, cmd, token, sign, requestTime, body);
        ImCallbackVerifier.ImCallbackResponse groupBeforeSend = this.groupBeforeSendMsgCallbackService.handle(sdkAppId, cmd, token, sign, requestTime, body);
        if (groupBeforeSend != null) {
            return groupBeforeSend;
        }
        ImCallbackVerifier.ImCallbackResponse before = this.beforeSendMsgCallbackService.handle(sdkAppId, cmd, token, sign, requestTime, body);
        if (before != null) {
            return before;
        }
        this.attachmentImBindService.handleAfterSendRaw(body);
        if (this.archiveProperties.enabled() && this.recallService != null && this.recallService.isRecallCommand(cmd)) {
            this.recallService.syncRecall(sdkAppId, cmd, token, sign, requestTime, body);
            if (this.archiveProducer != null) {
                this.archiveProducer.publishGroupRecall(cmd, body);
            }
            this.groupMessageMonitorService.tryForwardAsync(cmd, body);
            return ImCallbackVerifier.ImCallbackResponse.ok();
        }
        if (this.archiveProperties.enabled() && this.archiveProducer != null && this.archiveProducer.isAfterSendCommand(cmd)) {
            ImCallbackVerifier.ImCallbackResponse response = this.chatPushCallbackService.handle(sdkAppId, cmd, token, sign, requestTime, body);
            this.archiveProducer.publishAfterSend(sdkAppId, cmd, token, sign, requestTime, body);
            this.groupMessageMonitorService.tryForwardAsync(cmd, body);
            return response;
        }
        ImCallbackVerifier.ImCallbackResponse pushResponse = this.chatPushCallbackService.handle(sdkAppId, cmd, token, sign, requestTime, body);
        this.groupMessageMonitorService.tryForwardAsync(cmd, body);
        return pushResponse;
    }
}
