package com.chat99.server.group;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImUserIdService;
import com.chat99.server.notify.SystemNotifyProperties;
import com.chat99.server.push.PushDisplayNameResolver;
import com.chat99.server.realtime.GroupRealtimePublisher;
import com.chat99.server.realtime.GroupRealtimePublisher.GroupChangedEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 入群邀请/审批的系统号 C2C（{@link #NOTIFY_TYPE}）。默认关闭；在线/离线请用 TCP/Push {@code group_changed}。
 */
@Service
@ConditionalOnProperty(prefix = "chat99.group-join", name = "official-notify-enabled", havingValue = "true")
public class GroupJoinNotifyService {

    private static final Logger log = LoggerFactory.getLogger(GroupJoinNotifyService.class);
    public static final String NOTIFY_TYPE = "GROUP_JOIN_NOTIFY";

    private final SystemNotifyProperties notifyProps;
    private final ImAdminClient imAdmin;
    private final ImUserIdService imUserIdService;
    private final PushDisplayNameResolver displayNames;

    public GroupJoinNotifyService(SystemNotifyProperties notifyProps,
                                  ImAdminClient imAdmin,
                                  ImUserIdService imUserIdService,
                                  PushDisplayNameResolver displayNames) {
        this.notifyProps = notifyProps;
        this.imAdmin = imAdmin;
        this.imUserIdService = imUserIdService;
        this.displayNames = displayNames;
    }

    @EventListener
    public void onGroupChanged(GroupChangedEvent event) {
        if (event == null || event.action() == null) {
            return;
        }
        if (!GroupRealtimePublisher.ACTION_JOIN_APPLICATION_PENDING.equals(event.action())
            && !GroupRealtimePublisher.ACTION_JOIN_APPLICATION_HANDLED.equals(event.action())) {
            return;
        }
        String senderId = notifyProps.senderUserId();
        if (senderId == null || senderId.isBlank()) {
            return;
        }
        for (String targetUserId : event.targetUserIds()) {
            if (targetUserId == null || targetUserId.isBlank()) {
                continue;
            }
            try {
                String desc = buildDesc(targetUserId, event);
                if (desc == null) {
                    continue;
                }
                imAdmin.sendCustomC2c(senderId, imUserIdService.toIm(targetUserId), buildPayload(event), desc);
                log.info("group join notify sent to={} groupId={} action={}",
                    targetUserId, event.groupId(), event.action());
            } catch (Exception e) {
                log.warn("group join notify failed to={} groupId={} err={}",
                    targetUserId, event.groupId(), e.getMessage());
            }
        }
    }

    private Map<String, Object> buildPayload(GroupChangedEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", NOTIFY_TYPE);
        payload.put("groupId", event.groupId());
        payload.put("action", event.action());
        if (event.detail() != null) {
            payload.putAll(event.detail());
        }
        if (event.operatorUserId() != null) {
            payload.put("operatorUserId", event.operatorUserId());
        }
        return payload;
    }

    private String buildDesc(String targetUserId, GroupChangedEvent event) {
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

        if (GroupRealtimePublisher.ACTION_JOIN_APPLICATION_HANDLED.equals(event.action())) {
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
        }
        return null;
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
}
