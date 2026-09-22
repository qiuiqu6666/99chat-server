package com.chat99.server.im;

import com.chat99.server.call.CallWebhookService;
import com.chat99.server.common.AppSettingService;
import com.chat99.server.notify.PlatformWalletNoticeProperties;
import com.chat99.server.notify.SystemNotifyProperties;
import com.chat99.server.push.ConversationNotifyService;
import com.chat99.server.push.PushAvatarResolver;
import com.chat99.server.push.PushConfigService;
import com.chat99.server.push.PushFocusService;
import com.chat99.server.push.PushMessage;
import com.chat99.server.push.PushService;
import com.chat99.server.push.VoipCallPushTrigger;
import com.chat99.server.user.User;
import com.chat99.server.user.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ImChatPushCallbackService {

    private static final Logger log = LoggerFactory.getLogger(ImChatPushCallbackService.class);
    private static final String CMD_C2C_AFTER = "C2C.CallbackAfterSendMsg";
    private static final String CMD_GROUP_AFTER = "Group.CallbackAfterSendMsg";

    private final PushConfigService pushConfig;
    private final AppSettingService settings;
    private final ImAdminClient imAdmin;
    private final PushService pushService;
    private final UserRepository userRepository;
    private final ImPushDedupStore dedupStore;
    private final SystemNotifyProperties systemNotifyProperties;
    private final PlatformWalletNoticeProperties walletNoticeProperties;
    private final ImCallbackVerifier callbackVerifier;
    private final VoipCallPushTrigger voipCallPushTrigger;
    private final PushAvatarResolver pushAvatarResolver;
    private final ConversationNotifyService conversationNotifyService;
    private final GroupMemberCacheService groupMemberCacheService;
    private final GroupPushAggregationService groupPushAggregationService;
    private final PushFocusService pushFocusService;
    private final CallWebhookService callWebhookService;
    private final ObjectMapper json;
    private final ImChatPushPreviewService pushPreviewService;
    private final ImChatPushAvatarSupport pushAvatarSupport;
    private final ImUserIdService imUserIdService;

    public ImChatPushCallbackService(PushConfigService pushConfig,
                                     AppSettingService settings,
                                     ImAdminClient imAdmin,
                                     PushService pushService,
                                     UserRepository userRepository,
                                     ImPushDedupStore dedupStore,
                                     SystemNotifyProperties systemNotifyProperties,
                                     PlatformWalletNoticeProperties walletNoticeProperties,
                                     ImCallbackVerifier callbackVerifier,
                                     VoipCallPushTrigger voipCallPushTrigger,
                                     PushAvatarResolver pushAvatarResolver,
                                     ConversationNotifyService conversationNotifyService,
                                     GroupMemberCacheService groupMemberCacheService,
                                     GroupPushAggregationService groupPushAggregationService,
                                     PushFocusService pushFocusService,
                                     CallWebhookService callWebhookService,
                                     ObjectMapper json,
                                     ImChatPushPreviewService pushPreviewService,
                                     ImChatPushAvatarSupport pushAvatarSupport,
                                     ImUserIdService imUserIdService) {
        this.pushConfig = pushConfig;
        this.settings = settings;
        this.imAdmin = imAdmin;
        this.pushService = pushService;
        this.userRepository = userRepository;
        this.dedupStore = dedupStore;
        this.systemNotifyProperties = systemNotifyProperties;
        this.walletNoticeProperties = walletNoticeProperties;
        this.callbackVerifier = callbackVerifier;
        this.voipCallPushTrigger = voipCallPushTrigger;
        this.pushAvatarResolver = pushAvatarResolver;
        this.conversationNotifyService = conversationNotifyService;
        this.groupMemberCacheService = groupMemberCacheService;
        this.groupPushAggregationService = groupPushAggregationService;
        this.pushFocusService = pushFocusService;
        this.callWebhookService = callWebhookService;
        this.json = json;
        this.pushPreviewService = pushPreviewService;
        this.pushAvatarSupport = pushAvatarSupport;
        this.imUserIdService = imUserIdService;
    }

    public ImCallbackVerifier.ImCallbackResponse handle(String sdkAppId,
                                                        String command,
                                                        String callbackToken,
                                                        String sign,
                                                        String requestTime,
                                                        String rawBody) {
        Map<String, Object> body = parseBody(rawBody);
        String resolvedCommand = firstNonBlank(command, str(body.get("CallbackCommand")));
        mergeCallRecordIfNeeded(body, resolvedCommand);
        // 来电 VoIP：与聊天 Push 开关解耦，在线/离线都发（不看 OnlineOnlyFlag）
        tryVoipPushIfAfterSend(resolvedCommand, body);

        if (!pushConfig.isImCallbackEnabled() || !pushConfig.isChatPushEnabled()) {
            return ImCallbackVerifier.ImCallbackResponse.ok();
        }
        if (!pushService.enabled()) {
            return ImCallbackVerifier.ImCallbackResponse.ok();
        }

        if (!CMD_C2C_AFTER.equals(resolvedCommand) && !CMD_GROUP_AFTER.equals(resolvedCommand)) {
            return ImCallbackVerifier.ImCallbackResponse.ok();
        }

        validateSdkAppId(sdkAppId);
        if (sign != null && !sign.isBlank()) {
            callbackVerifier.verifySignature(sign, requestTime);
        } else {
            callbackVerifier.verifyQueryToken(callbackToken);
        }

        processAfterSendBody(body, resolvedCommand);
        return ImCallbackVerifier.ImCallbackResponse.ok();
    }

    /** Kafka Push Consumer 或 legacy webhook 路径调用。 */
    public void processAfterSend(String sdkAppId, String command, String rawBody) {
        Map<String, Object> body = parseBody(rawBody);
        String resolvedCommand = firstNonBlank(command, str(body.get("CallbackCommand")));
        mergeCallRecordIfNeeded(body, resolvedCommand);
        tryVoipPushIfAfterSend(resolvedCommand, body);

        if (!pushConfig.isImCallbackEnabled() || !pushConfig.isChatPushEnabled()) {
            return;
        }
        if (!pushService.enabled()) {
            return;
        }
        if (!CMD_C2C_AFTER.equals(resolvedCommand) && !CMD_GROUP_AFTER.equals(resolvedCommand)) {
            return;
        }
        validateSdkAppId(sdkAppId);
        processAfterSendBody(body, resolvedCommand);
    }

    private void mergeCallRecordIfNeeded(Map<String, Object> body, String resolvedCommand) {
        if (CMD_C2C_AFTER.equals(resolvedCommand)) {
            callWebhookService.tryMergeFromImBody(body);
        }
    }

    private void tryVoipPushIfAfterSend(String resolvedCommand, Map<String, Object> body) {
        if (CMD_C2C_AFTER.equals(resolvedCommand) || CMD_GROUP_AFTER.equals(resolvedCommand)) {
            voipCallPushTrigger.tryFromImBody(body);
        }
    }

    private void processAfterSendBody(Map<String, Object> body, String resolvedCommand) {
        if (intVal(body.get("OnlineOnlyFlag")) == 1) {
            return;
        }
        if (intVal(body.get("SendMsgResult")) != 0) {
            return;
        }

        String fromAccount = str(body.get("From_Account"));
        if (fromAccount == null || fromAccount.isBlank()) {
            return;
        }
        if (shouldSkipSender(fromAccount)) {
            return;
        }

        Object msgBodyRaw = body.get("MsgBody");
        if (!(msgBodyRaw instanceof List<?> msgBody)) {
            return;
        }

        if (CMD_C2C_AFTER.equals(resolvedCommand)) {
            handleC2c(body, fromAccount, msgBody);
        } else {
            handleGroup(body, fromAccount, msgBody);
        }
    }

    private void handleC2c(Map<String, Object> body, String fromAccount, List<?> msgBody) {
        String toAccount = str(body.get("To_Account"));
        if (toAccount == null || toAccount.isBlank() || fromAccount.equals(toAccount)) {
            return;
        }
        String msgKey = firstNonBlank(str(body.get("MsgKey")), str(body.get("MsgId")));
        String dedupKey = "c2c|" + msgKey + "|" + toAccount;
        if (!dedupStore.markIfNew(dedupKey)) {
            log.debug("im chat push duplicate dedupKey={}", dedupKey);
            return;
        }

        String toBiz = imUserIdService.toBusinessForDisplay(toAccount);
        String fromBiz = imUserIdService.toBusinessForDisplay(fromAccount);
        String senderTitle = resolveDisplayName(fromBiz);
        ImChatPushPreviewService.PreviewContext ctx = new ImChatPushPreviewService.PreviewContext(
            toBiz, "c2c", fromBiz, senderTitle, null);
        ImChatPushPreviewService.ChatPushPreview preview =
            pushPreviewService.previewC2c(ctx, msgBody);
        if (preview.skip()) {
            return;
        }
        if (conversationNotifyService.isMuted(toBiz, "c2c", fromBiz)) {
            log.debug("im chat push skipped: muted to={} from={}", toBiz, fromBiz);
            return;
        }
        if (pushFocusService.isFocusedOnConversation(toBiz, "c2c", fromBiz, null)) {
            log.debug("im chat push skipped: push focus to={} from={}", toBiz, fromBiz);
            return;
        }

        PushMessage message = buildChatMessage(
            preview.title(), preview.body(), "c2c", fromBiz, null, msgKey,
            pushAvatarSupport.resolveAvatarUrl(body, msgBody,
                pushAvatarResolver.resolveUserAvatarUrl(fromBiz)));
        pushService.sendChatToUser(toBiz, message, pushConfig.isChatPushSkipWhenOnline());
        log.info("im chat push c2c to={} from={} msgKey={}", toBiz, fromBiz, msgKey);
    }

    private void handleGroup(Map<String, Object> body, String fromAccount, List<?> msgBody) {
        String groupId = str(body.get("GroupId"));
        if (groupId == null || groupId.isBlank()) {
            return;
        }
        String msgKey = firstNonBlank(str(body.get("MsgId")), str(body.get("MsgSeq")));
        ImAdminClient.GroupBaseInfo groupInfo = imAdmin.getGroupBaseInfo(groupId).orElse(null);
        String groupName = groupInfo != null && groupInfo.name() != null && !groupInfo.name().isBlank()
            ? groupInfo.name() : "群消息";
        String groupAvatarUrl = pushAvatarSupport.resolveAvatarUrl(body, msgBody,
            firstNonBlank(
                groupInfo != null ? groupInfo.faceUrl() : null,
                pushAvatarResolver.resolveGroupAvatarUrl(groupId)));
        // 自建 Push 身份字段锁定业务号；数字 IM 仅 TIM SDK
        String fromBiz = imUserIdService.toBusinessForDisplay(fromAccount);
        String senderTitle = resolveDisplayName(fromBiz);
        if (pushPreviewService.shouldSkipMessage(msgBody)) {
            return;
        }
        String msgBodyJson;
        try {
            msgBodyJson = json.writeValueAsString(msgBody);
        } catch (JsonProcessingException e) {
            log.warn("im chat push group msgBody serialize failed groupId={} err={}", groupId, e.getMessage());
            return;
        }

        List<String> membersIm = groupMemberCacheService.memberUserIds(groupId);
        Map<String, String> imToBiz = imUserIdService.toBusinessForDisplayBatch(membersIm);
        List<String> members = membersIm.stream()
            .filter(id -> id != null && !id.isBlank())
            .map(id -> imToBiz.getOrDefault(id.trim(), id.trim()))
            .distinct()
            .toList();
        ImGroupMentionSupport.GroupMentions mentions =
            ImGroupMentionSupport.parse(body.get("GroupAtInfo"));
        Set<String> mentionedBiz = mentions.userIds().stream()
            .filter(id -> id != null && !id.isBlank())
            .map(imUserIdService::toBusinessForDisplay)
            .collect(Collectors.toCollection(HashSet::new));
        GroupPushAggregationService.GroupMessageEvent event =
            new GroupPushAggregationService.GroupMessageEvent(
                groupId,
                fromBiz,
                senderTitle,
                msgKey,
                groupName,
                msgBodyJson,
                groupAvatarUrl,
                mentions.atAll(),
                mentionedBiz);
        int enqueued = groupPushAggregationService.enqueue(event, members);
        if (enqueued > 0) {
            log.info("im chat push group enqueued groupId={} from={} members={} msgKey={}",
                groupId, fromBiz, enqueued, msgKey);
        }
    }

    private PushMessage buildChatMessage(String title, String body, String chatType,
                                           String fromAccount, String groupId, String msgKey,
                                           String avatarUrl) {
        PushMessage message = PushMessage.of(title, body)
            .withData("type", "im_chat")
            .withData("chatType", chatType)
            .withData("fromAccount", fromAccount);
        if (groupId != null) {
            message = message.withData("groupId", groupId);
        }
        if (msgKey != null) {
            message = message.withData("msgKey", msgKey);
        }
        if (avatarUrl != null && !avatarUrl.isBlank()) {
            message = message.withData("avatarUrl", avatarUrl.trim());
            message = message.withData("avatarThumbUrl", avatarUrl.trim());
        }
        String threadId = "group".equals(chatType) && groupId != null && !groupId.isBlank()
            ? "group_" + groupId
            : "c2c_" + fromAccount;
        return message.withApnsGrouping(threadId, threadId);
    }

    private String resolveDisplayName(String businessUserId) {
        return userRepository.findByUserId(businessUserId)
            .map(User::getNickname)
            .filter(n -> n != null && !n.isBlank())
            .orElse(businessUserId);
    }

    private boolean shouldSkipSender(String fromAccount) {
        Set<String> skip = new HashSet<>();
        skip.add(systemNotifyProperties.senderUserId());
        skip.add(walletNoticeProperties.senderUserId());
        for (String configured : pushConfig.getSkipSenderIds()) {
            if (configured != null && !configured.isBlank()) {
                skip.add(configured.trim());
            }
        }
        return skip.contains(fromAccount);
    }

    private Map<String, Object> parseBody(String rawBody) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = json.readValue(rawBody, Map.class);
            return parsed;
        } catch (JsonProcessingException e) {
            log.warn("im callback invalid json: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_JSON");
        }
    }

    private void validateSdkAppId(String sdkAppId) {
        if (sdkAppId == null || sdkAppId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
        Set<String> allowed = allowedSdkAppIds();
        if (!allowed.isEmpty() && !allowed.contains(sdkAppId.trim())) {
            log.warn("im callback rejected sdkAppId={}", sdkAppId);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
    }

    private Set<String> allowedSdkAppIds() {
        String csv = pushConfig.getAllowedSdkAppIds();
        if (csv != null && !csv.isBlank()) {
            return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        }
        int configured = settings.getInt(AppSettingService.IM_SDK_APP_ID, 0);
        if (configured != 0) {
            return Set.of(String.valueOf(configured));
        }
        return Set.of();
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static int intVal(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
