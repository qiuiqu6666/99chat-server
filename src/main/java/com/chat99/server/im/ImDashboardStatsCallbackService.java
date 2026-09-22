package com.chat99.server.im;

import com.chat99.server.adminapi.AdminDashboardCounterService;
import com.chat99.server.common.AppSettingService;
import com.chat99.server.group.GroupProjectionService;
import com.chat99.server.group.UserOwnedGroupService;
import com.chat99.server.push.PushConfigService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 首页看板统计：与 Push 解耦，IM 回调一到即计数（消息 / 建群）。
 */
@Service
public class ImDashboardStatsCallbackService {

    private static final Logger log = LoggerFactory.getLogger(ImDashboardStatsCallbackService.class);

    static final String CMD_C2C_AFTER = "C2C.CallbackAfterSendMsg";
    static final String CMD_GROUP_AFTER = "Group.CallbackAfterSendMsg";
    static final String CMD_GROUP_CREATE = "Group.CallbackAfterCreateGroup";
    static final String CMD_GROUP_DESTROY = "Group.CallbackAfterGroupDestroyed";

    private final PushConfigService pushConfig;
    private final AppSettingService settings;
    private final ImCallbackVerifier callbackVerifier;
    private final AdminDashboardCounterService dashboardCounter;
    private final ImPushDedupStore dedupStore;
    private final UserOwnedGroupService ownedGroupService;
    private final GroupProjectionService groupProjection;
    private final ImUserIdService imUserIdService;
    private final ObjectMapper json;

    public ImDashboardStatsCallbackService(PushConfigService pushConfig,
                                           AppSettingService settings,
                                           ImCallbackVerifier callbackVerifier,
                                           AdminDashboardCounterService dashboardCounter,
                                           ImPushDedupStore dedupStore,
                                           UserOwnedGroupService ownedGroupService,
                                           GroupProjectionService groupProjection,
                                           ImUserIdService imUserIdService,
                                           ObjectMapper json) {
        this.pushConfig = pushConfig;
        this.settings = settings;
        this.callbackVerifier = callbackVerifier;
        this.dashboardCounter = dashboardCounter;
        this.dedupStore = dedupStore;
        this.ownedGroupService = ownedGroupService;
        this.groupProjection = groupProjection;
        this.imUserIdService = imUserIdService;
        this.json = json;
    }

    /** 记录看板指标；鉴权失败抛异常，与 Push 回调一致。 */
    public void recordStats(String sdkAppId,
                            String command,
                            String callbackToken,
                            String sign,
                            String requestTime,
                            String rawBody) {
        Map<String, Object> body = parseBody(rawBody);
        String resolvedCommand = firstNonBlank(command, str(body.get("CallbackCommand")));
        if (!isStatsCommand(resolvedCommand)) {
            return;
        }

        validateSdkAppId(sdkAppId);
        if (sign != null && !sign.isBlank()) {
            callbackVerifier.verifySignature(sign, requestTime);
        } else {
            callbackVerifier.verifyQueryToken(callbackToken);
        }

        switch (resolvedCommand) {
            case CMD_C2C_AFTER -> recordC2cMessage(body);
            case CMD_GROUP_AFTER -> recordGroupMessage(body);
            case CMD_GROUP_CREATE -> recordGroupCreated(body);
            case CMD_GROUP_DESTROY -> recordGroupDestroyed(body);
            default -> { }
        }
    }

    private void recordC2cMessage(Map<String, Object> body) {
        if (intVal(body.get("SendMsgResult")) != 0) {
            return;
        }
        String msgKey = firstNonBlank(str(body.get("MsgKey")), str(body.get("MsgId")));
        String toAccount = str(body.get("To_Account"));
        if (msgKey == null || toAccount == null) {
            return;
        }
        String dedupKey = "dash|c2c|" + msgKey + "|" + toAccount;
        if (!dedupStore.markIfNew(dedupKey)) {
            return;
        }
        dashboardCounter.incrementC2cMessage();
        log.debug("dashboard stat c2c +1 msgKey={}", msgKey);
    }

    private void recordGroupMessage(Map<String, Object> body) {
        if (intVal(body.get("SendMsgResult")) != 0) {
            return;
        }
        String groupId = str(body.get("GroupId"));
        String msgKey = firstNonBlank(str(body.get("MsgId")), str(body.get("MsgSeq")), str(body.get("MsgKey")));
        if (groupId == null || msgKey == null) {
            return;
        }
        String dedupKey = "dash|group|" + groupId + "|" + msgKey;
        if (!dedupStore.markIfNew(dedupKey)) {
            return;
        }
        dashboardCounter.incrementGroupMessage();
        log.debug("dashboard stat group msg +1 groupId={} msgKey={}", groupId, msgKey);
    }

    private void recordGroupCreated(Map<String, Object> body) {
        String groupId = firstNonBlank(str(body.get("GroupId")), str(body.get("NewGroupId")));
        if (groupId == null) {
            return;
        }
        String dedupKey = "dash|group-create|" + groupId;
        if (!dedupStore.markIfNew(dedupKey)) {
            return;
        }
        dashboardCounter.incrementGroupsCreated();
        String owner = str(body.get("Owner_Account"));
        String groupType = str(body.get("Type"));
        String ownerBiz = owner == null || owner.isBlank()
            ? owner
            : imUserIdService.toBusinessForDisplay(owner);
        ownedGroupService.recordCreated(ownerBiz, groupType, groupId);
        // 群资料投影仅由 POST /group → syncFullGroupFromIm 写入，回调不再 insert group_profile，避免并发 duplicate。
        log.info("dashboard stat group created +1 groupId={} owner={} type={}", groupId, ownerBiz, groupType);
    }

    private void recordGroupDestroyed(Map<String, Object> body) {
        String groupId = str(body.get("GroupId"));
        if (groupId == null) {
            return;
        }
        String dedupKey = "dash|group-destroy|" + groupId;
        if (!dedupStore.markIfNew(dedupKey)) {
            return;
        }
        ownedGroupService.recordDestroyed(groupId);
        groupProjection.onGroupDismissed(groupId);
        log.info("dashboard stat group destroyed groupId={}", groupId);
    }

    private static boolean isStatsCommand(String command) {
        return CMD_C2C_AFTER.equals(command)
            || CMD_GROUP_AFTER.equals(command)
            || CMD_GROUP_CREATE.equals(command)
            || CMD_GROUP_DESTROY.equals(command);
    }

    private Map<String, Object> parseBody(String rawBody) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = json.readValue(rawBody, Map.class);
            return parsed;
        } catch (JsonProcessingException e) {
            log.warn("im dashboard callback invalid json: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_JSON");
        }
    }

    private void validateSdkAppId(String sdkAppId) {
        if (sdkAppId == null || sdkAppId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
        Set<String> allowed = allowedSdkAppIds();
        if (!allowed.isEmpty() && !allowed.contains(sdkAppId.trim())) {
            log.warn("im dashboard callback rejected sdkAppId={}", sdkAppId);
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
