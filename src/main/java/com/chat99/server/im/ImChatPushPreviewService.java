package com.chat99.server.im;

import com.chat99.server.chatattachment.ChatAttachmentCustomElemSupport;
import com.chat99.server.push.PushDisplayNameResolver;
import com.chat99.server.user.User;
import com.chat99.server.user.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ImChatPushPreviewService {

    private static final Logger log = LoggerFactory.getLogger(ImChatPushPreviewService.class);
    private static final Set<String> SKIP_BUSINESS_IDS = Set.of(
        "av_call", "lk_call", "platform_wallet_notice", "wallet_order", "announcement", "register_welcome",
        "user_typing_status", "red_packet_claim_notice", "friend_became_friends", "rtc_call");
    private static final Set<String> STRUCTURED_BUSINESS_IDS = Set.of(
        "wallet_transfer", "wallet_red_packet", "wallet_group_transfer",
        "contact_card", "group_create", "group_dismissed", "link", "chat.attachment");

    private final ObjectMapper json;
    private final UserRepository userRepository;
    private final PushDisplayNameResolver displayNameResolver;
    private final ImAdminClient imAdminClient;
    private final ImUserIdService imUserIdService;

    public ImChatPushPreviewService(ObjectMapper json,
                                    UserRepository userRepository,
                                    PushDisplayNameResolver displayNameResolver,
                                    ImAdminClient imAdminClient,
                                    ImUserIdService imUserIdService) {
        this.json = json;
        this.userRepository = userRepository;
        this.displayNameResolver = displayNameResolver;
        this.imAdminClient = imAdminClient;
        this.imUserIdService = imUserIdService;
    }

    public record ChatPushPreview(String title, String body, boolean skip) {}

    public record PreviewContext(
        String recipientUserId,
        String chatType,
        String fromAccount,
        String senderTitle,
        String groupName
    ) {}

    public ChatPushPreview previewC2c(PreviewContext ctx, List<?> msgBody) {
        if (shouldSkipMessage(msgBody)) {
            return new ChatPushPreview("", "", true);
        }
        String body = summarizeMsgBody(ctx, msgBody);
        String title = ctx.senderTitle() != null && !ctx.senderTitle().isBlank()
            ? ctx.senderTitle() : ctx.fromAccount();
        return new ChatPushPreview(title, body, false);
    }

    public ChatPushPreview previewGroup(PreviewContext ctx, List<?> msgBody) {
        if (shouldSkipMessage(msgBody)) {
            return new ChatPushPreview("", "", true);
        }
        String summary = summarizeMsgBody(ctx, msgBody);
        String title = ctx.groupName() != null && !ctx.groupName().isBlank() ? ctx.groupName() : "群消息";
        String body;
        if (isGroupTipOnlyMessage(msgBody)) {
            body = summary;
        } else {
            String sender = ctx.senderTitle() != null && !ctx.senderTitle().isBlank()
                ? ctx.senderTitle() : ctx.fromAccount();
            body = sender + ": " + summary;
        }
        return new ChatPushPreview(title, body, false);
    }

    public boolean shouldSkipMessage(List<?> msgBody) {
        if (msgBody == null || msgBody.isEmpty()) {
            return true;
        }
        for (Object item : msgBody) {
            if (!(item instanceof Map<?, ?> msg)) {
                continue;
            }
            String type = str(msg.get("MsgType"));
            Object contentRaw = msg.get("MsgContent");
            if (!(contentRaw instanceof Map<?, ?> content)) {
                continue;
            }
            if ("TIMCustomElem".equals(type)) {
                if (isBusinessCustom(str(content.get("Data")), str(content.get("Desc")))) {
                    return true;
                }
            }
            if ("TIMGroupTipElem".equals(type) && ImChatGroupTipSupport.isSilentGroupTip(content)) {
                return true;
            }
        }
        return false;
    }

    public String summarizeMsgBody(PreviewContext ctx, List<?> msgBody) {
        if (msgBody == null || msgBody.isEmpty()) {
            return "新消息";
        }
        List<String> parts = new ArrayList<>();
        for (Object item : msgBody) {
            summarizeItem(ctx, item).ifPresent(parts::add);
        }
        if (parts.isEmpty()) {
            return "新消息";
        }
        return String.join(" ", parts);
    }

    private Optional<String> summarizeItem(PreviewContext ctx, Object item) {
        if (!(item instanceof Map<?, ?> msg)) {
            return Optional.empty();
        }
        String type = str(msg.get("MsgType"));
        Object contentRaw = msg.get("MsgContent");
        if (!(contentRaw instanceof Map<?, ?> content)) {
            return Optional.empty();
        }
        return switch (type) {
            case "TIMTextElem" -> optionalNonBlank(str(content.get("Text")));
            case "TIMCustomElem" -> summarizeCustom(
                ctx,
                str(content.get("Data")),
                str(content.get("Desc")),
                firstNonBlank(str(content.get("Extension")), str(content.get("Ext"))));
            case "TIMImageElem" -> Optional.of("[图片]");
            case "TIMSoundElem" -> Optional.of("[语音]");
            case "TIMVideoFileElem" -> Optional.of("[视频]");
            case "TIMFileElem" -> {
                String name = str(content.get("FileName"));
                yield name != null ? Optional.of("[文件] " + name) : Optional.of("[文件]");
            }
            case "TIMFaceElem" -> Optional.of("[表情]");
            case "TIMLocationElem" -> Optional.of("[位置]");
            case "TIMGroupTipElem" -> Optional.of(summarizeGroupTip(ctx, content));
            case "TIMRelayElem" -> summarizeRelay(content);
            default -> {
                log.debug("im chat push unknown MsgType={}", type);
                yield Optional.of("[消息]");
            }
        };
    }

    private Optional<String> summarizeRelay(Map<?, ?> content) {
        String title = str(content.get("Title"));
        if (title != null) {
            return Optional.of("[合并转发] " + title);
        }
        return Optional.of("[合并转发]");
    }

    private String summarizeGroupTip(PreviewContext ctx, Map<?, ?> content) {
        ImChatGroupTipSupport.DisplayNameLookup lookup = buildLookup(ctx.recipientUserId());
        return ImChatGroupTipSupport.summarize(content, lookup);
    }

    private Optional<String> summarizeCustom(PreviewContext ctx, String dataJson, String desc, String extension) {
        Map<String, Object> data = parseData(dataJson);
        String businessId = businessId(data);
        // group_tip：锁屏正文必须用 previewAbstract / action 拼装，禁止 businessID 或 Desc=group_tip
        if (GroupTipCustomPushSupport.isGroupTip(data)) {
            String tipBody = GroupTipCustomPushSupport.pushBody(data);
            log.debug("im chat push group_tip action={} previewAbstractPresent={} bodyLen={}",
                str(data.get("action")),
                str(data.get("previewAbstract")) != null && !str(data.get("previewAbstract")).isBlank(),
                tipBody.length());
            return Optional.of(tipBody);
        }
        if (businessId != null && STRUCTURED_BUSINESS_IDS.contains(businessId)) {
            Optional<String> structured = summarizeStructuredCustom(ctx, businessId, data);
            if (structured.isPresent()) {
                return structured;
            }
        }
        if (isUsableCustomDesc(desc, businessId)) {
            return Optional.of(desc.trim());
        }
        if (data != null) {
            String topLevel = firstNonBlank(
                str(data.get("text")), str(data.get("content")), str(data.get("title")),
                str(data.get("previewAbstract")));
            if (topLevel != null && !isTypeNameAsBody(topLevel, businessId)) {
                return Optional.of(topLevel);
            }
        }
        Optional<String> fromExtension = summarizeExtension(extension);
        if (fromExtension.isPresent()) {
            return fromExtension;
        }
        // 禁止把 businessID / 类型名原样当推送正文（如 group_tip）
        return Optional.of("[消息]");
    }

    /** Desc 若只是 businessID / 类型枚举，不当作用户可见正文。 */
    private static boolean isUsableCustomDesc(String desc, String businessId) {
        if (desc == null || desc.isBlank()) {
            return false;
        }
        return !isTypeNameAsBody(desc.trim(), businessId);
    }

    private static boolean isTypeNameAsBody(String text, String businessId) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.trim();
        if (GroupTipCustomPushSupport.BUSINESS_ID.equalsIgnoreCase(t)) {
            return true;
        }
        if (businessId != null && !businessId.isBlank() && businessId.equalsIgnoreCase(t)) {
            return true;
        }
        // "[group_tip]" / "group_tip" 一类
        if (t.length() >= 2 && t.startsWith("[") && t.endsWith("]")) {
            String inner = t.substring(1, t.length() - 1).trim();
            return GroupTipCustomPushSupport.BUSINESS_ID.equalsIgnoreCase(inner)
                || (businessId != null && businessId.equalsIgnoreCase(inner));
        }
        return false;
    }

    private Optional<String> summarizeStructuredCustom(PreviewContext ctx, String businessId,
                                                       Map<String, Object> data) {
        return switch (businessId) {
            case "wallet_transfer" -> Optional.of(summarizeWalletTransfer(ctx, data));
            case "wallet_red_packet" -> Optional.of(summarizeWalletRedPacket(data));
            case "wallet_group_transfer" -> Optional.of(summarizeWalletGroupTransfer(data));
            case "contact_card" -> Optional.of(summarizeContactCard(ctx, data));
            case "group_create" -> Optional.of(summarizeGroupCreate(ctx, data));
            case "group_dismissed" -> Optional.of(summarizeGroupDismissed(data));
            case "link" -> Optional.of(summarizeLink(data));
            case "chat.attachment" -> Optional.of(summarizeChatAttachment(data));
            default -> Optional.empty();
        };
    }

    private String summarizeWalletTransfer(PreviewContext ctx, Map<String, Object> data) {
        String fromUserId = str(data.get("fromUserId"));
        String toUserId = str(data.get("toUserId"));
        String recipient = ctx.recipientUserId();
        if (recipient != null && recipient.equals(toUserId)) {
            return "[转账] 转账给你";
        }
        if (recipient != null && recipient.equals(fromUserId)) {
            String name = resolveName(ctx, toUserId, null);
            return "[转账] 转账给 " + name;
        }
        return "[转账]";
    }

    private String summarizeWalletRedPacket(Map<String, Object> data) {
        String greeting = str(data.get("greeting"));
        if (greeting != null) {
            return "[红包] " + greeting;
        }
        return "[红包]";
    }

    private String summarizeWalletGroupTransfer(Map<String, Object> data) {
        String greeting = str(data.get("greeting"));
        if (greeting != null && !greeting.isBlank()) {
            return "[群转账] " + greeting;
        }
        return "[群转账]";
    }

    private String summarizeContactCard(PreviewContext ctx, Map<String, Object> data) {
        String inline = firstNonBlank(
            str(data.get("nickname")),
            str(data.get("contactNickname")),
            str(data.get("name")));
        String contactUserId = firstNonBlank(str(data.get("contactUserId")), str(data.get("userId")));
        String name = resolveName(ctx, contactUserId, inline);
        return "[个人名片] " + name;
    }

    private String summarizeGroupCreate(PreviewContext ctx, Map<String, Object> data) {
        String content = str(data.get("content"));
        if (content != null) {
            return content;
        }
        String opName = resolveOpUserName(ctx, data);
        String groupName = str(data.get("groupName"));
        if (groupName != null) {
            return opName + "创建了群聊「" + groupName + "」";
        }
        return opName + "创建了群聊";
    }

    private String summarizeGroupDismissed(Map<String, Object> data) {
        String text = firstNonBlank(str(data.get("text")), str(data.get("content")));
        return text != null ? text : "[群聊已解散]";
    }

    private String summarizeLink(Map<String, Object> data) {
        String text = firstNonBlank(
            str(data.get("text")), str(data.get("title")), str(data.get("description")));
        return text != null ? text : "业务消息";
    }

    private String summarizeChatAttachment(Map<String, Object> data) {
        ChatAttachmentCustomElemSupport.AttachmentMessage msg =
            ChatAttachmentCustomElemSupport.parseMessage(data);
        if (msg != null) {
            return ChatAttachmentCustomElemSupport.pushPreview(msg);
        }
        String kind = str(data.get("kind"));
        String name = str(data.get("name"));
        String label = switch (kind == null ? "" : kind) {
            case "image" -> "[图片]";
            case "audio" -> "[语音]";
            case "video" -> "[视频]";
            default -> "[文件]";
        };
        return name != null && !name.isBlank() ? label + " " + name : label;
    }

    private Optional<String> summarizeExtension(String extension) {
        if (extension == null || extension.isBlank()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> ext = json.readValue(extension, new TypeReference<>() {});
            String text = firstNonBlank(str(ext.get("title")), str(ext.get("text")), str(ext.get("url")));
            return text != null ? Optional.of(text) : Optional.empty();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private String resolveOpUserName(PreviewContext ctx, Map<String, Object> data) {
        Object opUser = data.get("opUser");
        if (opUser instanceof Map<?, ?> map) {
            String inline = firstNonBlank(str(map.get("nickname")), str(map.get("name")));
            String userId = str(map.get("userId"));
            return resolveName(ctx, userId, inline);
        }
        return resolveName(ctx, str(data.get("opUserId")), null);
    }

    private String resolveName(PreviewContext ctx, String userId, String inlineName) {
        if (inlineName != null && !inlineName.isBlank()) {
            return inlineName.trim();
        }
        if (userId == null || userId.isBlank()) {
            return "好友";
        }
        ImChatGroupTipSupport.DisplayNameLookup lookup = buildLookup(ctx.recipientUserId());
        return lookup.resolve(userId, null);
    }

    private ImChatGroupTipSupport.DisplayNameLookup buildLookup(String recipientUserId) {
        Map<String, String> cache = new HashMap<>();
        return (userId, inlineName) -> {
            if (inlineName != null && !inlineName.isBlank()) {
                return inlineName.trim();
            }
            if (userId == null || userId.isBlank()) {
                return "成员";
            }
            return cache.computeIfAbsent(userId, id -> lookupDisplayName(recipientUserId, id));
        };
    }

    private String lookupDisplayName(String recipientUserId, String userId) {
        if (recipientUserId != null && !recipientUserId.isBlank()) {
            String friendName = displayNameResolver.resolveCallerDisplayName(recipientUserId, userId);
            if (friendName != null && !friendName.isBlank() && !friendName.equals(userId)) {
                return friendName;
            }
        }
        return userRepository.findByUserId(userId)
            .map(User::getNickname)
            .filter(n -> n != null && !n.isBlank())
            .orElseGet(() -> portraitNickname(userId));
    }

    private String portraitNickname(String userId) {
        try {
            String imAccount = imUserIdService.toIm(userId);
            Map<String, ImAdminClient.ProfilePortrait> profiles =
                imAdminClient.getPortraitProfiles(Set.of(imAccount));
            ImAdminClient.ProfilePortrait profile = profiles.get(imAccount);
            if (profile != null && profile.nickname() != null && !profile.nickname().isBlank()) {
                return profile.nickname();
            }
        } catch (Exception e) {
            log.debug("im chat push portrait lookup failed userId={}: {}", userId, e.getMessage());
        }
        return userId;
    }

    private boolean isGroupTipOnlyMessage(List<?> msgBody) {
        boolean hasTip = false;
        for (Object item : msgBody) {
            if (!(item instanceof Map<?, ?> msg)) {
                return false;
            }
            String type = str(msg.get("MsgType"));
            if ("TIMGroupTipElem".equals(type)) {
                hasTip = true;
                continue;
            }
            if ("TIMCustomElem".equals(type)) {
                Object contentRaw = msg.get("MsgContent");
                if (!(contentRaw instanceof Map<?, ?> content)) {
                    return false;
                }
                Map<String, Object> data = parseData(str(content.get("Data")));
                if (GroupTipCustomPushSupport.isGroupTip(data)) {
                    hasTip = true;
                    continue;
                }
            }
            return false;
        }
        return hasTip;
    }

    private boolean isBusinessCustom(String dataJson, String desc) {
        if (containsBusinessMarker(desc)) {
            return true;
        }
        if (dataJson == null || dataJson.isBlank()) {
            return false;
        }
        if (dataJson.contains("av_call") || dataJson.contains("lk_call")
            || dataJson.contains("platform_wallet_notice")
            || dataJson.contains("wallet_order") || dataJson.contains("user_typing_status")
            || dataJson.contains("red_packet_claim_notice") || dataJson.contains("friend_became_friends")
            || dataJson.contains("rtc_call")) {
            return true;
        }
        Map<String, Object> data = parseData(dataJson);
        String businessId = businessId(data);
        return businessId != null && SKIP_BUSINESS_IDS.contains(businessId);
    }

    private boolean containsBusinessMarker(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.contains("av_call") || text.contains("lk_call") || text.contains("rtc_call");
    }

    private Map<String, Object> parseData(String dataJson) {
        if (dataJson == null || dataJson.isBlank()) {
            return null;
        }
        try {
            return json.readValue(dataJson, new TypeReference<>() {});
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String businessId(Map<String, Object> data) {
        if (data == null) {
            return null;
        }
        return firstNonBlank(
            str(data.get("businessID")), str(data.get("customType")), str(data.get("type")));
    }

    private static Optional<String> optionalNonBlank(String value) {
        return value != null && !value.isBlank() ? Optional.of(value.trim()) : Optional.empty();
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
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
