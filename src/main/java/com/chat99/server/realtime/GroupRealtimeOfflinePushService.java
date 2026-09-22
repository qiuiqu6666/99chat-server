package com.chat99.server.realtime;

import com.chat99.server.push.PushDisplayNameResolver;
import com.chat99.server.push.PushMessage;
import com.chat99.server.push.PushService;
import com.chat99.server.realtime.GroupRealtimePublisher.GroupChangedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class GroupRealtimeOfflinePushService {

    private static final Logger log = LoggerFactory.getLogger(GroupRealtimeOfflinePushService.class);
    public static final String PUSH_TYPE = "group_changed";

    private final PushService pushService;
    private final RealtimeProperties props;
    private final PushDisplayNameResolver displayNames;
    private final ObjectMapper json;

    public GroupRealtimeOfflinePushService(PushService pushService,
                                           RealtimeProperties props,
                                           PushDisplayNameResolver displayNames,
                                           ObjectMapper json) {
        this.pushService = pushService;
        this.props = props;
        this.displayNames = displayNames;
        this.json = json;
    }

    public void sendIfTcpUnreachable(String targetUserId, GroupChangedEvent event) {
        if (!props.offlinePushEnabled()
            || !props.groupOfflinePushEnabled()
            || !pushService.enabled()) {
            return;
        }
        PushMessage message = buildMessage(targetUserId, event);
        if (message == null) {
            return;
        }
        pushService.sendChatToUser(targetUserId, message, false);
        log.info("group realtime offline push userId={} groupId={} action={} changeEventId={}",
            targetUserId, event.groupId(), event.action(), event.changeEventId());
    }

    /**
     * 广播类群事件：不全员离线推，避免万人群推送风暴。
     */
    public boolean shouldSkipOfflinePushForBroadcast(String action) {
        if (action == null) {
            return false;
        }
        return switch (action) {
            case GroupRealtimePublisher.ACTION_GROUP_MUTE_ALL_CHANGED,
                 GroupRealtimePublisher.ACTION_MEMBER_MUTED,
                 GroupRealtimePublisher.ACTION_MEMBER_UNMUTED,
                 GroupRealtimePublisher.ACTION_MEMBER_ROLE_CHANGED -> true;
            default -> false;
        };
    }

    /**
     * 广播类事件仍允许离线推的白名单（通常为被操作者）。
     */
    public List<String> offlinePushAllowlist(GroupChangedEvent event) {
        if (event == null || event.action() == null) {
            return List.of();
        }
        return switch (event.action()) {
            case GroupRealtimePublisher.ACTION_GROUP_MUTE_ALL_CHANGED -> List.of();
            case GroupRealtimePublisher.ACTION_MEMBER_MUTED,
                 GroupRealtimePublisher.ACTION_MEMBER_UNMUTED,
                 GroupRealtimePublisher.ACTION_MEMBER_ROLE_CHANGED ->
                event.memberUserIds() == null ? List.of() : List.copyOf(event.memberUserIds());
            default -> List.of();
        };
    }

    private PushMessage buildMessage(String targetUserId, GroupChangedEvent event) {
        String title = switch (event.action()) {
            case GroupRealtimePublisher.ACTION_JOIN_APPLICATION_PENDING -> "入群待审批";
            case GroupRealtimePublisher.ACTION_JOIN_APPLICATION_HANDLED -> "入群审批结果";
            case GroupRealtimePublisher.ACTION_GROUP_SYSTEM_NOTICE -> "群通知";
            default -> "群聊更新";
        };
        String body = switch (event.action()) {
            case GroupRealtimePublisher.ACTION_MEMBER_ADDED -> buildMemberAddedBody(targetUserId, event);
            case GroupRealtimePublisher.ACTION_MEMBER_REMOVED -> "你已被移出群聊或群成员有变动";
            case GroupRealtimePublisher.ACTION_MEMBER_LEFT -> "有成员退出了群聊";
            case GroupRealtimePublisher.ACTION_OWNER_CHANGED -> "群主已变更";
            case GroupRealtimePublisher.ACTION_GROUP_DISMISSED -> "群聊已解散";
            case GroupRealtimePublisher.ACTION_MEMBER_ROLE_CHANGED -> "群管理员有变动";
            case GroupRealtimePublisher.ACTION_GROUP_MUTE_ALL_CHANGED -> "全员禁言状态已变更";
            case GroupRealtimePublisher.ACTION_JOIN_APPLICATION_PENDING,
                 GroupRealtimePublisher.ACTION_JOIN_APPLICATION_HANDLED ->
                buildJoinApplicationBody(targetUserId, event);
            case GroupRealtimePublisher.ACTION_GROUP_SYSTEM_NOTICE ->
                buildSystemNoticeBody(targetUserId, event);
            default -> null;
        };
        if (body == null) {
            return null;
        }
        Map<String, String> data = flattenPayload(GroupChangedPayloadBuilder.build(event));
        data.put("type", PUSH_TYPE);
        PushMessage message = PushMessage.of(title, body).withDataMap(data);
        String collapseId = "group_" + event.groupId() + "_" + event.changeEventId();
        return message.withApnsGrouping(collapseId, PUSH_TYPE);
    }

    private Map<String, String> flattenPayload(Map<String, Object> payload) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof List<?> || value instanceof Map<?, ?>) {
                out.put(key, writeJson(value));
            } else {
                out.put(key, String.valueOf(value));
            }
        }
        return out;
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("group offline push json serialize failed: {}", e.getMessage());
            return String.valueOf(value);
        }
    }

    private String buildJoinApplicationBody(String targetUserId, GroupChangedEvent event) {
        Map<String, Object> detail = event.detail() == null ? Map.of() : event.detail();
        String applicationType = str(detail.get("applicationType"));
        String fromUserId = str(detail.get("fromUserId"));
        String toUserId = str(detail.get("toUserId"));
        String fromName = displayNames.resolveCallerDisplayName(targetUserId, fromUserId);
        String toName = displayNames.resolveCallerDisplayName(targetUserId, toUserId);

        if (GroupRealtimePublisher.ACTION_JOIN_APPLICATION_PENDING.equals(event.action())) {
            if ("invite".equalsIgnoreCase(applicationType)) {
                return fromName + " 邀请 " + toName + " 入群，待你审批";
            }
            return fromName + " 申请加入群聊，待你审批";
        }

        boolean approved = isApproved(detail);
        if ("invite".equalsIgnoreCase(applicationType)) {
            if (targetUserId.equals(fromUserId)) {
                return approved
                    ? "你邀请的 " + toName + " 已通过审批"
                    : "你邀请的 " + toName + " 未通过审批";
            }
            if (targetUserId.equals(toUserId) && approved) {
                return "你已被批准加入群聊";
            }
            return null;
        }
        if (targetUserId.equals(fromUserId)) {
            return approved ? "你的加群申请已通过" : "你的加群申请未通过";
        }
        return null;
    }

    private String buildSystemNoticeBody(String targetUserId, GroupChangedEvent event) {
        Map<String, Object> detail = event.detail() == null ? Map.of() : event.detail();
        String type = str(detail.get("type"));
        String groupName = str(detail.get("groupName"));
        String displayGroup = groupName == null || groupName.isBlank() ? "群聊" : groupName;
        String operatorUserId = str(detail.get("operatorUserId"));
        String noticeTargetUserId = str(detail.get("targetUserId"));
        String operatorName = displayNames.resolveCallerDisplayName(targetUserId, operatorUserId);
        String targetName = displayNames.resolveCallerDisplayName(targetUserId, noticeTargetUserId);
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "grant_administrator" -> targetUserId.equals(noticeTargetUserId)
                ? "你已被设为「" + displayGroup + "」管理员"
                : operatorName + " 将 " + targetName + " 设为「" + displayGroup + "」管理员";
            case "revoke_administrator" -> targetUserId.equals(noticeTargetUserId)
                ? "你在「" + displayGroup + "」的管理员身份已被取消"
                : operatorName + " 取消了 " + targetName + " 在「" + displayGroup + "」的管理员身份";
            case "transfer_owner" -> targetUserId.equals(noticeTargetUserId)
                ? "你已成为「" + displayGroup + "」群主"
                : "「" + displayGroup + "」群主已转让给 " + targetName;
            default -> null;
        };
    }

    private static boolean isApproved(Map<String, Object> detail) {
        String result = str(detail.get("result"));
        if (result != null) {
            return "approved".equalsIgnoreCase(result);
        }
        return "approved".equalsIgnoreCase(str(detail.get("status")));
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private static String buildMemberAddedBody(String targetUserId, GroupChangedEvent event) {
        if (targetUserId != null && event.memberUserIds() != null && event.memberUserIds().contains(targetUserId)) {
            return "你已加入群聊";
        }
        return "有新成员加入了群聊";
    }
}
