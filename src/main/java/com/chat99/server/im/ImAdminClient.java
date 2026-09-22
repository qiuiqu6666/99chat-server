package com.chat99.server.im;

import com.chat99.server.common.AppSettingService;
import com.chat99.server.group.GroupJoinOption;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencentyun.TLSSigAPIv2;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ImAdminClient {

    private static final Logger log = LoggerFactory.getLogger(ImAdminClient.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final ImProperties props;
    private final ImGroupRoleCache roleCache;
    private final int sdkAppId;
    private final TLSSigAPIv2 api;
    private final OkHttpClient http = new OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build();
    private final ObjectMapper json = new ObjectMapper();
    private final Random rng = new Random();

    public ImAdminClient(AppSettingService settings, ImProperties props, ImGroupRoleCache roleCache) {
        this.props = props;
        this.roleCache = roleCache;
        this.sdkAppId = settings.getInt(AppSettingService.IM_SDK_APP_ID, 0);
        String key = settings.get(AppSettingService.IM_KEY).orElse(null);
        if (sdkAppId != 0 && key != null) {
            this.api = new TLSSigAPIv2(sdkAppId, key);
        } else {
            this.api = null;
        }
        log.info("ImAdminClient ready sdkAppId={} restBase={} roleCache={}",
            sdkAppId, props.restBaseUrl(), Boolean.TRUE.equals(props.roleCacheEnabled()));
    }

    public void accountImport(String userId, String nickname, String faceUrl) {
        if (api == null) {
            log.warn("IM not configured; skip accountImport userId={}", userId);
            return;
        }
        String url = buildUrl("im_open_login_svc/account_import");
        Map<String, Object> body = Map.of(
            "Identifier", userId,
            "Nick", nickname == null ? "" : nickname,
            "FaceUrl", faceUrl == null ? "" : faceUrl);
        post(url, body, "accountImport userId=" + userId);
    }

    /**
     * 查询 IM 账号是否存在（im_open_login_svc/account_check）。
     * 未配置 IM 或查询失败时返回 false。
     */
    public boolean accountExists(String userId) {
        if (api == null || userId == null || userId.isBlank()) {
            throw new IllegalStateException("IM not configured or userId blank");
        }
        String url = buildUrl("im_open_login_svc/account_check");
        Map<String, Object> body = Map.of("CheckItem", List.of(Map.of("UserID", userId)));
        Map<?, ?> resp = postReturning(url, body, "accountCheck userId=" + userId);
        if (resp == null) {
            throw new IllegalStateException("IM account_check returned null for userId=" + userId);
        }
        Object code = resp.get("ErrorCode");
        if (code instanceof Number n && n.intValue() != 0) {
            throw new IllegalStateException("IM account_check failed for userId=" + userId + " code=" + code);
        }
        Object resultList = resp.get("ResultItem");
        if (!(resultList instanceof List<?> arr) || arr.isEmpty()) {
            throw new IllegalStateException("IM account_check empty result for userId=" + userId);
        }
        Object first = arr.get(0);
        if (!(first instanceof Map<?, ?> entry)) {
            throw new IllegalStateException("IM account_check bad result format for userId=" + userId);
        }
        Object accountStatus = entry.get("AccountStatus");
        if (accountStatus instanceof String s && "Imported".equalsIgnoreCase(s.trim())) {
            return true;
        }
        // 兼容旧版 account_check 应答字段
        Object existCode = entry.get("Exist");
        return existCode instanceof Number n && n.intValue() == 1;
    }

    public void profileUpdate(String userId, String nickname) {
        if (api == null) return;
        String url = buildUrl("profile/portrait_set");
        Map<String, Object> body = Map.of(
            "From_Account", userId,
            "ProfileItem", List.of(Map.of("Tag", "Tag_Profile_IM_Nick", "Value", nickname)));
        post(url, body, "profileUpdate userId=" + userId);
    }

    public void profileUpdateFaceUrl(String userId, String faceUrl) {
        if (api == null) {
            log.warn("IM not configured; skip profileUpdateFaceUrl userId={}", userId);
            return;
        }
        String url = buildUrl("profile/portrait_set");
        Map<String, Object> body = Map.of(
            "From_Account", userId,
            "ProfileItem", List.of(Map.of(
                "Tag", "Tag_Profile_IM_Image",
                "Value", faceUrl == null ? "" : faceUrl)));
        post(url, body, "profileUpdateFaceUrl userId=" + userId);
    }

    public void modifyGroupFaceUrl(String groupId, String faceUrl) {
        if (api == null) {
            throw new ImRestException("IM_NOT_CONFIGURED", 0);
        }
        if (groupId == null || groupId.isBlank()) {
            throw new ImRestException("INVALID_INPUT", 0);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("GroupId", groupId.trim());
        body.put("FaceUrl", faceUrl == null ? "" : faceUrl);
        postRequired("group_open_http_svc/modify_group_base_info", body,
            "modifyGroupFaceUrl groupId=" + groupId);
    }

    public void modifyGroupAppDefinedGameid(String groupId, String gameid) {
        if (api == null) {
            throw new ImRestException("IM_NOT_CONFIGURED", 0);
        }
        if (groupId == null || groupId.isBlank()) {
            throw new ImRestException("INVALID_INPUT", 0);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("GroupId", groupId.trim());
        body.put("AppDefinedData", List.of(Map.of(
            "Key", "gameid",
            "Value", gameid == null ? "" : gameid)));
        postRequired("group_open_http_svc/modify_group_base_info", body,
            "modifyGroupAppDefinedGameid groupId=" + groupId);
    }

    public void modifyGroupJoinOptions(String groupId, GroupJoinOption applyJoinOption, GroupJoinOption inviteJoinOption) {
        if (api == null) {
            throw new ImRestException("IM_NOT_CONFIGURED", 0);
        }
        if (groupId == null || groupId.isBlank()) {
            throw new ImRestException("INVALID_INPUT", 0);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("GroupId", groupId.trim());
        if (applyJoinOption != null) {
            body.put("ApplyJoinOption", applyJoinOption.imApplyValue());
        }
        if (inviteJoinOption != null) {
            body.put("InviteJoinOption", inviteJoinOption.imInviteValue());
        }
        if (body.size() <= 1) {
            throw new ImRestException("INVALID_INPUT", 0);
        }
        postRequired("group_open_http_svc/modify_group_base_info", body,
            "modifyGroupJoinOptions groupId=" + groupId);
    }

    public void modifyGroupBaseInfo(String groupId, String name, String notice) {
        if (api == null) {
            throw new ImRestException("IM_NOT_CONFIGURED", 0);
        }
        if (groupId == null || groupId.isBlank()) {
            throw new ImRestException("INVALID_INPUT", 0);
        }
        if ((name == null || name.isBlank()) && notice == null) {
            throw new ImRestException("INVALID_INPUT", 0);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("GroupId", groupId.trim());
        if (name != null && !name.isBlank()) {
            body.put("Name", name.trim());
        }
        if (notice != null) {
            body.put("Notification", notice);
        }
        postRequired("group_open_http_svc/modify_group_base_info", body,
            "modifyGroupBaseInfo groupId=" + groupId);
    }

    public void modifyMemberNameCard(String groupId, String userId, String nameCard) {
        if (api == null) {
            throw new ImRestException("IM_NOT_CONFIGURED", 0);
        }
        // 与 modifyMemberImRole / mute 一致：腾讯要求顶层 Member_Account，不是 MemberList
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("GroupId", groupId.trim());
        body.put("Member_Account", userId.trim());
        body.put("NameCard", nameCard == null ? "" : nameCard);
        postRequired("group_open_http_svc/modify_group_member_info", body,
            "modifyMemberNameCard groupId=" + groupId + " userId=" + userId);
    }

    public java.util.Optional<String> getMemberNameCard(String groupId, String userId) {
        if (api == null || groupId == null || groupId.isBlank() || userId == null || userId.isBlank()) {
            return java.util.Optional.empty();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("GroupId", groupId.trim());
        body.put("Limit", 1);
        body.put("Offset", 0);
        body.put("MemberInfoFilter", List.of(Map.of(
            "Member_Account", userId.trim(),
            "Role_Filter", List.of("Owner", "Admin", "Member"))));
        Map<?, ?> raw = postReturning(
            buildUrl("group_open_http_svc/get_group_member_info"), body, "getMemberNameCard");
        if (raw == null || !imOk(raw)) {
            return java.util.Optional.empty();
        }
        Object list = raw.get("MemberList");
        if (!(list instanceof List<?> arr) || arr.isEmpty()) {
            return java.util.Optional.empty();
        }
        Object first = arr.get(0);
        if (!(first instanceof Map<?, ?> m)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(str(m.get("NameCard")));
    }

    /**
     * 查询成员禁言状态（get_group_member_info · MutedUntil）。
     * 仅当当前仍处于禁言中时返回截止时间戳（秒）。
     */
    public java.util.Optional<Long> getMemberMuteUntil(String groupId, String userId) {
        if (api == null || groupId == null || groupId.isBlank() || userId == null || userId.isBlank()) {
            return java.util.Optional.empty();
        }
        return findMemberMutedUntilRaw(groupId.trim(), userId.trim())
            .flatMap(ImAdminClient::activeMuteUntilSec);
    }

    /** IM 返回的 MutedUntil 为禁言截止 Unix 秒；仅当大于当前时间才算有效禁言。 */
    static java.util.Optional<Long> activeMuteUntilSec(long mutedUntilSec) {
        if (mutedUntilSec <= 0) {
            return java.util.Optional.empty();
        }
        long nowSec = System.currentTimeMillis() / 1000;
        return mutedUntilSec > nowSec ? java.util.Optional.of(mutedUntilSec) : java.util.Optional.empty();
    }

    private java.util.Optional<Long> findMemberMutedUntilRaw(String groupId, String userId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("GroupId", groupId);
        body.put("Member_Account_Filter", List.of(userId));
        body.put("Limit", 1);
        body.put("Offset", 0);
        Map<?, ?> raw = postReturning(
            buildUrl("group_open_http_svc/get_group_member_info"), body, "getMemberMuteUntil");
        if (raw == null || !imOk(raw)) {
            return java.util.Optional.empty();
        }
        Object list = raw.get("MemberList");
        if (!(list instanceof List<?> arr) || arr.isEmpty()) {
            return java.util.Optional.empty();
        }
        Object first = arr.get(0);
        if (!(first instanceof Map<?, ?> m)) {
            return java.util.Optional.empty();
        }
        if (!userId.equals(str(m.get("Member_Account")))) {
            return java.util.Optional.empty();
        }
        Object muteUntil = m.get("MutedUntil");
        if (muteUntil instanceof Number n) {
            return java.util.Optional.of(n.longValue());
        }
        return java.util.Optional.of(0L);
    }

    /** 查询全员禁言状态（get_group_info · ShutUpAllMember）。 */
    public java.util.Optional<Boolean> isGroupAllMuted(String groupId) {
        return fetchGroupAdminInfo(groupId)
            .map(GroupAdminInfo::shutUpAllMember)
            .map(s -> "On".equalsIgnoreCase(s));
    }

    /** Returns "Owner" / "Admin" / "Member" / "NotMember", or null on error / not configured. */
    public String getRoleInGroup(String groupId, String userId) {
        return getRoleInGroupResult(groupId, userId).role();
    }

    public ImRoleFetchResult getRoleInGroupResult(String groupId, String userId) {
        if (api == null) {
            log.warn("getRoleInGroup api==null (IM not configured) groupId={} userId={}", groupId, userId);
            return ImRoleFetchResult.notConfigured();
        }
        var cached = roleCache.get(groupId, userId);
        if (cached.isPresent()) {
            log.debug("getRoleInGroup cacheHit groupId={} userId={} role={}", groupId, userId, cached.get());
            return ImRoleFetchResult.ok(cached.get());
        }
        String url = buildUrl("group_open_http_svc/get_role_in_group");
        Map<String, Object> body = Map.of(
            "GroupId", groupId,
            "User_Account", List.of(userId));
        Map<?, ?> resp = postReturning(url, body, "getRoleInGroup groupId=" + groupId + " userId=" + userId);
        if (resp == null) {
            log.warn("getRoleInGroup resp==null groupId={} userId={}", groupId, userId);
            return ImRoleFetchResult.error(null);
        }
        log.debug("getRoleInGroup raw groupId={} userId={} resp={}", groupId, userId, resp);
        Object code = resp.get("ErrorCode");
        if (!(code instanceof Number n) || n.intValue() != 0) {
            log.warn("getRoleInGroup non-zero code groupId={} userId={} resp={}", groupId, userId, resp);
            if (isQuotaError(resp)) {
                return ImRoleFetchResult.rateLimited(code instanceof Number q ? q.intValue() : 60007);
            }
            return ImRoleFetchResult.error(code instanceof Number e ? e.intValue() : null);
        }
        Object list = resp.get("UserIdList");
        if (!(list instanceof List<?> arr) || arr.isEmpty()) {
            log.warn("getRoleInGroup empty UserIdList groupId={} userId={} resp={}", groupId, userId, resp);
            return ImRoleFetchResult.error(0);
        }
        Object first = arr.get(0);
        if (!(first instanceof Map<?, ?> entry)) {
            log.warn("getRoleInGroup first not map groupId={} userId={} first={}", groupId, userId, first);
            return ImRoleFetchResult.error(0);
        }
        Object role = entry.get("Role");
        String r = role == null ? null : role.toString();
        if (r != null) {
            roleCache.put(groupId, userId, r);
        }
        log.debug("getRoleInGroup groupId={} userId={} role={}", groupId, userId, r);
        return ImRoleFetchResult.ok(r);
    }

    public record ImRoleFetchResult(String role, Status status, Integer errorCode) {
        public enum Status { OK, RATE_LIMITED, ERROR, NOT_CONFIGURED }

        static ImRoleFetchResult ok(String role) {
            return new ImRoleFetchResult(role, Status.OK, 0);
        }

        static ImRoleFetchResult rateLimited(Integer code) {
            return new ImRoleFetchResult(null, Status.RATE_LIMITED, code);
        }

        static ImRoleFetchResult error(Integer code) {
            return new ImRoleFetchResult(null, Status.ERROR, code);
        }

        static ImRoleFetchResult notConfigured() {
            return new ImRoleFetchResult(null, Status.NOT_CONFIGURED, null);
        }

        public boolean rateLimited() {
            return status == Status.RATE_LIMITED;
        }

        public boolean failed() {
            return status != Status.OK;
        }
    }

    public String createOfficialAccount(String officialAccountUserId, String ownerAccount, String name,
                                        String introduction, String faceUrl, String organization,
                                        int maxSubscriberNum) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (officialAccountUserId != null && !officialAccountUserId.isBlank()) {
            body.put("OfficialAccountUserID", officialAccountUserId);
        }
        body.put("Owner_Account", ownerAccount);
        body.put("Name", name);
        if (introduction != null) {
            body.put("Introduction", introduction);
        }
        if (faceUrl != null) {
            body.put("FaceUrl", faceUrl);
        }
        if (organization != null) {
            body.put("Organization", organization);
        }
        body.put("MaxSubscriberNum", maxSubscriberNum);
        Map<String, Object> resp = postRequired(
            "official_account_open_http_svc/create_official_account", body, "createOfficialAccount");
        Object id = resp.get("OfficialAccountUserID");
        if (id == null || id.toString().isBlank()) {
            throw new ImRestException("IM_MISSING_OFFICIAL_ACCOUNT_ID", 0);
        }
        return id.toString();
    }

    public void modifyOfficialAccount(String officialAccountId, String name, String introduction,
                                      String faceUrl, String organization, Integer maxSubscriberNum) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Official_Account", officialAccountId);
        if (name != null) {
            body.put("Name", name);
        }
        if (introduction != null) {
            body.put("Introduction", introduction);
        }
        if (faceUrl != null) {
            body.put("FaceUrl", faceUrl);
        }
        if (organization != null) {
            body.put("Organization", organization);
        }
        if (maxSubscriberNum != null) {
            body.put("MaxSubscriberNum", maxSubscriberNum);
        }
        postRequired("official_account_open_http_svc/modify_official_account_base_info", body,
            "modifyOfficialAccount id=" + officialAccountId);
    }

    public void destroyOfficialAccount(String officialAccountId) {
        postRequired("official_account_open_http_svc/destroy_official_account",
            Map.of("Official_Account", officialAccountId),
            "destroyOfficialAccount id=" + officialAccountId);
    }

    public List<Map<String, Object>> getOfficialAccountInfo(List<String> officialAccountIds) {
        if (officialAccountIds == null || officialAccountIds.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> idList = new ArrayList<>();
        for (String id : officialAccountIds) {
            idList.add(Map.of("Official_Account", id));
        }
        Map<String, Object> body = Map.of(
            "OfficialAccountIdList", idList,
            "ResponseFilter", Map.of("OfficialAccountBaseInfoFilter", List.of(
                "CreateTime", "Name", "Owner_Account", "LastMsgTime", "SubscriberNum",
                "Introduction", "FaceUrl", "Organization", "CustomString")));
        Map<String, Object> resp = postRequired(
            "official_account_open_http_svc/get_official_account_info", body, "getOfficialAccountInfo");
        Object list = resp.get("OfficialAccountInfoList");
        if (!(list instanceof List<?> arr)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : arr) {
            if (item instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) m;
                out.add(cast);
            }
        }
        return out;
    }

    public void addSubscriber(String officialAccountId, String subscriberUserId) {
        Map<String, Object> body = Map.of(
            "Official_Account", officialAccountId,
            "SubscriberList", List.of(Map.of("Subscriber_Account", subscriberUserId)));
        Map<String, Object> resp = postRequired(
            "official_account_open_http_svc/add_subscriber", body,
            "addSubscriber account=" + officialAccountId + " user=" + subscriberUserId);
        assertSubscriberResult(resp, subscriberUserId, "add");
    }

    public void deleteSubscriber(String officialAccountId, String subscriberUserId) {
        Map<String, Object> body = Map.of(
            "Official_Account", officialAccountId,
            "SubscriberList", List.of(Map.of("Subscriber_Account", subscriberUserId)));
        Map<String, Object> resp = postRequired(
            "official_account_open_http_svc/delete_subscriber", body,
            "deleteSubscriber account=" + officialAccountId + " user=" + subscriberUserId);
        assertSubscriberResult(resp, subscriberUserId, "delete");
    }

    public List<Map<String, Object>> getSubscribedOfficialAccounts(String subscriberUserId, int limit, int offset) {
        Map<String, Object> body = Map.of(
            "Subscriber_Account", subscriberUserId,
            "Limit", limit,
            "Offset", offset,
            "ResponseFilter", Map.of(
                "OfficialAccountBaseInfoFilter", List.of(
                    "CreateTime", "Name", "Owner_Account", "LastMsgTime", "SubscriberNum",
                    "Introduction", "FaceUrl", "Organization"),
                "SelfInfoFilter", List.of("SubscribeTime")));
        Map<String, Object> resp = postRequired(
            "official_account_open_http_svc/get_subscribed_official_account_list", body,
            "getSubscribedOfficialAccounts user=" + subscriberUserId);
        Object list = resp.get("OfficialAccountInfoList");
        if (!(list instanceof List<?> arr)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : arr) {
            if (item instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) m;
                out.add(cast);
            }
        }
        return out;
    }

    public Map<String, Object> sendOfficialAccountBroadcast(String officialAccountId, String text) {
        Map<String, Object> body = Map.of(
            "Official_Account", officialAccountId,
            "Random", rng.nextInt(Integer.MAX_VALUE),
            "MsgBody", List.of(Map.of(
                "MsgType", "TIMTextElem",
                "MsgContent", Map.of("Text", text))));
        return postRequired("official_account_open_http_svc/send_official_account_msg", body,
            "sendOfficialAccountBroadcast id=" + officialAccountId);
    }

    /** C2C 自定义消息（TIMCustomElem）。 */
    public void sendCustomC2c(String fromAccount, String toAccount, Map<String, Object> data) {
        sendCustomC2c(fromAccount, toAccount, data, null);
    }

    /** C2C 自定义消息；{@code desc} 写入 MsgContent.Desc（会话摘要 / 离线推送文案）。 */
    public void sendCustomC2c(String fromAccount, String toAccount, Map<String, Object> data, String desc) {
        Map<String, Object> body = newLinkedHashMapC2cSend(fromAccount, toAccount);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("Data", toCustomJson(data));
        content.put("Ext", "");
        content.put("Sound", "");
        if (desc != null && !desc.isBlank()) {
            content.put("Desc", desc);
        }
        body.put("MsgBody", List.of(Map.of(
            "MsgType", "TIMCustomElem",
            "MsgContent", content)));
        postRequired("openim/sendmsg", body,
            "sendCustomC2c from=" + fromAccount + " to=" + toAccount);
    }

    /** 群自定义消息（openim/send_group_msg）。 */
    public void sendCustomGroup(String fromAccount, String groupId, Map<String, Object> data) {
        sendCustomGroup(fromAccount, groupId, data, null, null, null);
    }

    /**
     * 群定向自定义消息：{@code toAccounts} 非空时仅 listed 成员收到（腾讯 IM To_Account，上限 50）。
     * {@code desc} 写入 MsgContent.Desc；{@code random} 可固定幂等重试（五分钟相同 Random 视为重复）。
     */
    public void sendCustomGroup(String fromAccount, String groupId, Map<String, Object> data,
                              List<String> toAccounts, String desc, Integer random) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("GroupId", groupId);
        body.put("From_Account", fromAccount);
        body.put("Random", random != null ? random : rng.nextInt(Integer.MAX_VALUE));
        if (toAccounts != null && !toAccounts.isEmpty()) {
            body.put("To_Account", toAccounts.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .distinct()
                .limit(50)
                .toList());
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("Data", toCustomJson(data));
        if (desc != null && !desc.isBlank()) {
            content.put("Desc", desc);
        }
        body.put("MsgBody", List.of(Map.of(
            "MsgType", "TIMCustomElem",
            "MsgContent", content)));
        postRequired("group_open_http_svc/send_group_msg", body,
            "sendCustomGroup group=" + groupId);
    }

    private String toCustomJson(Map<String, Object> data) {
        try {
            return json.writeValueAsString(data);
        } catch (IOException e) {
            throw new ImRestException("IM_CUSTOM_JSON", 0);
        }
    }

    /**
     * 双向强制加好友（sns/friend_add）。用于系统通知号与用户的默认好友关系。
     */
    public void addFriendBoth(String fromAccount, String toAccount, String addSource, String addWording) {
        if (api == null) {
            throw new ImRestException("IM_NOT_CONFIGURED", 0);
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("To_Account", toAccount);
        item.put("AddSource", addSource);
        if (addWording != null && !addWording.isBlank()) {
            item.put("AddWording", addWording);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("From_Account", fromAccount);
        body.put("AddFriendItem", List.of(item));
        body.put("AddType", "Add_Type_Both");
        body.put("ForceAddFlags", 1);
        Map<String, Object> resp = postRequired("sns/friend_add", body,
            "addFriendBoth from=" + fromAccount + " to=" + toAccount);
        assertFriendAddResult(resp, toAccount);
    }

    /**
     * 双向删除好友（sns/friend_delete）。
     * 幂等：对方已非好友（常见 30003/30004 等）视为成功。
     */
    public void deleteFriendBoth(String fromAccount, String toAccount) {
        if (api == null) {
            throw new ImRestException("IM_NOT_CONFIGURED", 0);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("From_Account", fromAccount);
        body.put("To_Account", List.of(toAccount));
        body.put("DeleteType", "Delete_Type_Both");
        Map<String, Object> resp = postRequired("sns/friend_delete", body,
            "deleteFriendBoth from=" + fromAccount + " to=" + toAccount);
        assertFriendDeleteResult(resp, toAccount);
    }

    /**
     * 更新单向好友备注（sns/friend_update · Tag_SNS_IM_Remark）。
     * {@code remark} 为 null/空白时写入空串以清空 IM 备注。
     */
    public void updateFriendRemark(String fromAccount, String toAccount, String remark) {
        if (api == null) {
            throw new ImRestException("IM_NOT_CONFIGURED", 0);
        }
        String value = remark == null ? "" : remark.trim();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("From_Account", fromAccount);
        body.put("UpdateItem", List.of(Map.of(
            "To_Account", toAccount,
            "SnsItem", List.of(Map.of(
                "Tag", "Tag_SNS_IM_Remark",
                "Value", value)))));
        Map<String, Object> resp = postRequired("sns/friend_update", body,
            "updateFriendRemark from=" + fromAccount + " to=" + toAccount);
        assertFriendUpdateResult(resp, toAccount);
    }

    public boolean isConfigured() {
        return api != null;
    }

    public void addBlackList(String fromAccount, String toAccount) {
        if (api == null) {
            throw new ImRestException("IM_NOT_CONFIGURED", 0);
        }
        String from = trimAccount(fromAccount);
        String to = trimAccount(toAccount);
        if (from.isEmpty() || to.isEmpty()) {
            throw new ImRestException("INVALID_INPUT", 0);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("From_Account", from);
        body.put("To_Account", List.of(to));
        Map<String, Object> resp = postRequired("sns/black_list_add", body,
            "addBlackList from=" + from + " to=" + to);
        assertBlackListWriteResult(resp, to, true);
    }

    public void deleteBlackList(String fromAccount, String toAccount) {
        if (api == null) {
            throw new ImRestException("IM_NOT_CONFIGURED", 0);
        }
        String from = trimAccount(fromAccount);
        String to = trimAccount(toAccount);
        if (from.isEmpty() || to.isEmpty()) {
            throw new ImRestException("INVALID_INPUT", 0);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("From_Account", from);
        body.put("To_Account", List.of(to));
        Map<String, Object> resp = postRequired("sns/black_list_delete", body,
            "deleteBlackList from=" + from + " to=" + to);
        assertBlackListWriteResult(resp, to, false);
    }

    public record BlackListEntry(String userId, Long addTimeSec) {}

    public record BlackListPage(List<BlackListEntry> items, int nextStartIndex) {}

    public BlackListPage listBlackList(String fromAccount, int startIndex, int maxLimited) {
        if (api == null) {
            throw new ImRestException("IM_NOT_CONFIGURED", 0);
        }
        String from = trimAccount(fromAccount);
        if (from.isEmpty()) {
            throw new ImRestException("INVALID_INPUT", 0);
        }
        int start = Math.max(startIndex, 0);
        int limit = Math.min(Math.max(maxLimited, 1), 100);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("From_Account", from);
        body.put("StartIndex", start);
        body.put("MaxLimited", limit);
        body.put("LastSequence", 0);
        Map<String, Object> resp = postRequired("sns/black_list_get", body,
            "listBlackList from=" + from);
        List<BlackListEntry> items = new ArrayList<>();
        Object list = resp.get("BlackListItem");
        if (list instanceof List<?> arr) {
            for (Object item : arr) {
                if (!(item instanceof Map<?, ?> entry)) {
                    continue;
                }
                Object account = entry.get("To_Account");
                if (account == null || account.toString().isBlank()) {
                    continue;
                }
                Long ts = null;
                Object stamp = entry.get("AddBlackTimeStamp");
                if (stamp instanceof Number n && n.longValue() > 0) {
                    ts = n.longValue();
                }
                items.add(new BlackListEntry(account.toString().trim(), ts));
            }
        }
        int next = 0;
        Object nextObj = resp.get("StartIndex");
        if (nextObj instanceof Number n) {
            next = Math.max(n.intValue(), 0);
        }
        return new BlackListPage(items, next);
    }

    /**
     * {@code CheckType=BlackCheckResult_Type_Both}。{@code Relation} 不是
     * {@code BlackCheckResult_Type_NO} 即任一侧拉黑。
     */
    public boolean isEitherInBlackList(String fromAccount, String toAccount) {
        if (api == null) {
            throw new ImRestException("IM_NOT_CONFIGURED", 0);
        }
        String from = trimAccount(fromAccount);
        String to = trimAccount(toAccount);
        if (from.isEmpty() || to.isEmpty() || from.equals(to)) {
            return false;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("From_Account", from);
        body.put("To_Account", List.of(to));
        body.put("CheckType", "BlackCheckResult_Type_Both");
        Map<String, Object> resp = postRequired("sns/black_list_check", body,
            "checkBlackList from=" + from + " to=" + to);
        Object list = resp.get("BlackListCheckItem");
        if (list == null) {
            list = resp.get("BlackListItem");
        }
        if (list == null) {
            list = resp.get("ResultItem");
        }
        if (!(list instanceof List<?> arr) || arr.isEmpty()) {
            throw new ImRestException("IM_REST_UNAVAILABLE", 0);
        }
        for (Object item : arr) {
            if (!(item instanceof Map<?, ?> entry)) {
                continue;
            }
            Object account = entry.get("To_Account");
            if (account == null || !to.equals(account.toString())) {
                continue;
            }
            Object result = entry.get("ResultCode");
            int itemCode = result instanceof Number n ? n.intValue() : 0;
            if (itemCode != 0) {
                throw new ImRestException("IM_REST_UNAVAILABLE", itemCode);
            }
            Object relation = entry.get("Relation");
            String rel = relation == null ? "" : relation.toString().trim();
            return !rel.isEmpty() && !"BlackCheckResult_Type_NO".equals(rel) && !"NO".equals(rel);
        }
        throw new ImRestException("IM_REST_UNAVAILABLE", 0);
    }

    private static String trimAccount(String account) {
        return account == null ? "" : account.trim();
    }

    private void assertBlackListWriteResult(Map<String, Object> resp, String toAccount, boolean adding) {
        Object list = resp.get("ResultItem");
        if (!(list instanceof List<?> arr)) {
            return;
        }
        for (Object item : arr) {
            if (!(item instanceof Map<?, ?> entry)) {
                continue;
            }
            Object account = entry.get("To_Account");
            if (account == null || !toAccount.equals(account.toString())) {
                continue;
            }
            Object result = entry.get("ResultCode");
            int code = result instanceof Number n ? n.intValue() : 0;
            if (code == 0 || code == 30001 || code == 30006 || (adding && code == 30007)) {
                return;
            }
            throw new ImRestException(adding ? "BLACK_LIST_ADD_FAILED" : "BLACK_LIST_DELETE_FAILED", code);
        }
    }

    /** 允许任何人加为好友，避免用户侧确认。 */
    public void setAllowTypeAllowAny(String userId) {
        if (api == null) {
            log.warn("IM not configured; skip setAllowTypeAllowAny userId={}", userId);
            return;
        }
        String url = buildUrl("profile/portrait_set");
        Map<String, Object> body = Map.of(
            "From_Account", userId,
            "ProfileItem", List.of(Map.of(
                "Tag", "Tag_Profile_IM_AllowType",
                "Value", "AllowType_Type_AllowAny")));
        post(url, body, "setAllowTypeAllowAny userId=" + userId);
    }

    /** 公众号/指定账号向用户发单聊文本（openim/sendmsg）。 */
    public void sendC2cText(String fromAccount, String toAccount, String text) {
        Map<String, Object> body = newLinkedHashMapC2cSend(fromAccount, toAccount);
        body.put("MsgBody", List.of(Map.of(
            "MsgType", "TIMTextElem",
            "MsgContent", Map.of("Text", text))));
        postRequired("openim/sendmsg", body,
            "sendC2cText from=" + fromAccount + " to=" + toAccount);
    }

    /** C2C 图片消息（TIMImageElem，含原图/大图/缩略图）。 */
    public void sendC2cImage(String fromAccount, String toAccount, ImC2cImageContent image) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("UUID", image.uuid());
        content.put("ImageFormat", image.imageFormat());
        content.put("ImageInfoArray", List.of(
            imageInfo(1, image.originSize(), image.width(), image.height(), image.originUrl()),
            imageInfo(2, image.largeSize(), 0, 0, image.largeUrl()),
            imageInfo(3, image.thumbSize(), image.thumbWidth(), image.thumbHeight(), image.thumbUrl())));
        sendC2cElem(fromAccount, toAccount, "TIMImageElem", content);
    }

    /** C2C 视频消息（TIMVideoFileElem，外部 URL + 封面）。 */
    public void sendC2cVideo(String fromAccount, String toAccount, ImC2cVideoContent video) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("VideoUrl", video.videoUrl());
        content.put("VideoUUID", java.util.UUID.randomUUID().toString());
        content.put("VideoSize", video.videoSize());
        content.put("VideoSecond", Math.max(1, video.videoSecond()));
        content.put("VideoFormat", video.videoFormat());
        content.put("VideoDownloadFlag", 2);
        content.put("ThumbUrl", video.thumbUrl());
        content.put("ThumbUUID", java.util.UUID.randomUUID().toString());
        content.put("ThumbSize", Math.max(1L, video.thumbSize()));
        content.put("ThumbWidth", Math.max(1, video.thumbWidth()));
        content.put("ThumbHeight", Math.max(1, video.thumbHeight()));
        content.put("ThumbFormat", "JPG");
        content.put("ThumbDownloadFlag", 2);
        sendC2cElem(fromAccount, toAccount, "TIMVideoFileElem", content);
    }

    public record NativeVideoSendResult(boolean accepted, String msgKey, Long msgSeq, int errorCode) {}

    /**
     * 聊天大视频代发：C2C SyncOtherMachine=1，群聊 From_Account 为业务发送者。
     * 使用调用方持久化的 Random，超时后必须原样重试。
     */
    public NativeVideoSendResult sendNativeVideo(boolean c2c,
                                                 String fromAccount,
                                                 String toAccountOrGroupId,
                                                 int random,
                                                 Map<String, Object> videoContent,
                                                 String cloudCustomData) {
        if (api == null) {
            throw new ImRestException("IM_NOT_CONFIGURED", 0);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        String path;
        String tag;
        if (c2c) {
            body.put("SyncOtherMachine", 1);
            body.put("From_Account", fromAccount);
            body.put("To_Account", toAccountOrGroupId);
            body.put("MsgRandom", random);
            disableImOfflinePush(body);
            path = "openim/sendmsg";
            tag = "sendNativeVideoC2c from=" + fromAccount + " to=" + toAccountOrGroupId;
        } else {
            body.put("GroupId", toAccountOrGroupId);
            body.put("From_Account", fromAccount);
            body.put("Random", random);
            disableImOfflinePush(body);
            path = "group_open_http_svc/send_group_msg";
            tag = "sendNativeVideoGroup from=" + fromAccount + " group=" + toAccountOrGroupId;
        }
        if (cloudCustomData != null && !cloudCustomData.isBlank()) {
            body.put("CloudCustomData", cloudCustomData);
        }
        body.put("MsgBody", List.of(Map.of(
            "MsgType", "TIMVideoFileElem",
            "MsgContent", videoContent)));
        Map<?, ?> raw = postReturning(buildUrl(path), body, tag);
        if (raw == null) {
            throw new ImRestException("IM_REST_UNAVAILABLE", 0);
        }
        Object code = raw.get("ErrorCode");
        int err = code instanceof Number n ? n.intValue() : 0;
        String msgKey = raw.get("MsgKey") == null ? null : raw.get("MsgKey").toString();
        Long msgSeq = null;
        if (raw.get("MsgSeq") instanceof Number n) {
            msgSeq = n.longValue();
        }
        if (err == 0 || err == 20004) {
            return new NativeVideoSendResult(true, msgKey, msgSeq, err);
        }
        Object info = raw.get("ErrorInfo");
        throw new ImRestException(info == null ? "IM_ERROR" : info.toString(), err);
    }

    private static Map<String, Object> imageInfo(int type, long size, int width, int height, String url) {
        Map<String, Object> imageInfo = new LinkedHashMap<>();
        imageInfo.put("Type", type);
        imageInfo.put("Size", Math.max(1L, size));
        imageInfo.put("Width", Math.max(0, width));
        imageInfo.put("Height", Math.max(0, height));
        imageInfo.put("URL", url);
        return imageInfo;
    }

    private void sendC2cElem(String fromAccount, String toAccount, String msgType, Map<String, Object> msgContent) {
        Map<String, Object> body = newLinkedHashMapC2cSend(fromAccount, toAccount);
        body.put("MsgBody", List.of(Map.of("MsgType", msgType, "MsgContent", msgContent)));
        postRequired("openim/sendmsg", body,
            "sendC2cElem type=" + msgType + " from=" + fromAccount + " to=" + toAccount);
    }

    /**
     * 服务端代发 C2C（公告/系统通知/钱包卡片等）统一关闭腾讯 IM 自带离线 Push，
     * 避免与 {@code PushService} 业务 Push 重复；聊天离线 Push 仍走 IM 回调 + 自建 Push。
     */
    private Map<String, Object> newLinkedHashMapC2cSend(String fromAccount, String toAccount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("SyncOtherMachine", 2);
        body.put("From_Account", fromAccount);
        body.put("To_Account", toAccount);
        body.put("MsgRandom", rng.nextInt(Integer.MAX_VALUE));
        disableImOfflinePush(body);
        return body;
    }

    private static void disableImOfflinePush(Map<String, Object> body) {
        body.put("OfflinePushInfo", Map.of("PushFlag", 0));
    }

    private void assertFriendAddResult(Map<String, Object> resp, String toAccount) {
        Object list = resp.get("ResultItem");
        if (!(list instanceof List<?> arr)) {
            return;
        }
        for (Object item : arr) {
            if (!(item instanceof Map<?, ?> entry)) {
                continue;
            }
            Object account = entry.get("To_Account");
            if (account == null || !toAccount.equals(account.toString())) {
                continue;
            }
            Object result = entry.get("ResultCode");
            int code = result instanceof Number n ? n.intValue() : 0;
            // 30001: 已是好友，补好友 Job 幂等
            if (code != 0 && code != 30001) {
                throw new ImRestException("FRIEND_ADD_FAILED", code);
            }
            return;
        }
    }

    private void assertFriendDeleteResult(Map<String, Object> resp, String toAccount) {
        Object top = resp.get("ErrorCode");
        int topCode = top instanceof Number n ? n.intValue() : 0;
        if (topCode != 0) {
            throw new ImRestException("FRIEND_DELETE_FAILED", topCode);
        }
        Object list = resp.get("ResultItem");
        if (!(list instanceof List<?> arr)) {
            return;
        }
        for (Object item : arr) {
            if (!(item instanceof Map<?, ?> entry)) {
                continue;
            }
            Object account = entry.get("To_Account");
            if (account == null || !toAccount.equals(account.toString())) {
                continue;
            }
            Object result = entry.get("ResultCode");
            int code = result instanceof Number n ? n.intValue() : 0;
            // 0 成功；30003/30004 常见「非好友」幂等
            if (code != 0 && code != 30003 && code != 30004) {
                throw new ImRestException("FRIEND_DELETE_FAILED", code);
            }
            return;
        }
    }

    private void assertFriendUpdateResult(Map<String, Object> resp, String toAccount) {
        Object top = resp.get("ErrorCode");
        int topCode = top instanceof Number n ? n.intValue() : 0;
        if (topCode != 0) {
            throw new ImRestException("FRIEND_UPDATE_FAILED", topCode);
        }
        Object list = resp.get("ResultItem");
        if (!(list instanceof List<?> arr)) {
            return;
        }
        for (Object item : arr) {
            if (!(item instanceof Map<?, ?> entry)) {
                continue;
            }
            Object account = entry.get("To_Account");
            if (account == null || !toAccount.equals(account.toString())) {
                continue;
            }
            Object result = entry.get("ResultCode");
            int code = result instanceof Number n ? n.intValue() : 0;
            if (code != 0) {
                throw new ImRestException("FRIEND_UPDATE_FAILED", code);
            }
            return;
        }
    }

    private void assertSubscriberResult(Map<String, Object> resp, String subscriberUserId, String op) {
        Object list = resp.get("SubscriberList");
        if (!(list instanceof List<?> arr)) {
            return;
        }
        for (Object item : arr) {
            if (!(item instanceof Map<?, ?> entry)) {
                continue;
            }
            Object account = entry.get("Subscriber_Account");
            if (account == null || !subscriberUserId.equals(account.toString())) {
                continue;
            }
            Object result = entry.get("Result");
            int code = result instanceof Number n ? n.intValue() : 0;
            boolean ok = "add".equals(op) ? (code == 1 || code == 2) : (code == 1 || code == 2);
            if (!ok) {
                throw new ImRestException("OFFICIAL_ACCOUNT_SUBSCRIBER_" + op.toUpperCase() + "_FAILED", 0);
            }
            return;
        }
    }

    /**
     * 批量拉取用户 IM 头像 URL（profile/portrait_get · Tag_Profile_IM_Image）。
     * 单次最多 100 个账号；未配置 IM 或失败时返回空 Map。
     */
    public Map<String, String> getPortraitImageUrls(Collection<String> userIds) {
        Map<String, ProfilePortrait> profiles = getPortraitProfiles(userIds);
        Map<String, String> out = new HashMap<>();
        profiles.forEach((id, p) -> {
            if (p.imageUrl() != null) {
                out.put(id, p.imageUrl());
            }
        });
        return out;
    }

    /** 好友数量（sns/friend_get）；IM 未配置或失败时返回 0。 */
    public int friendCount(String userId) {
        if (api == null || userId == null || userId.isBlank()) {
            return 0;
        }
        try {
            Map<String, Object> body = Map.of(
                "From_Account", userId,
                "StartIndex", 0);
            Map<?, ?> raw = postReturning(buildUrl("sns/friend_get"), body, "friendCount user=" + userId);
            if (raw == null) {
                return 0;
            }
            Object code = raw.get("ErrorCode");
            if (code instanceof Number n && n.intValue() != 0) {
                return 0;
            }
            Object total = raw.get("FriendNum");
            if (total instanceof Number num) {
                return num.intValue();
            }
            List<?> arr = friendListArray(raw);
            return arr.size();
        } catch (Exception e) {
            log.warn("friendCount failed userId={} {}", userId, e.getMessage());
            return 0;
        }
    }

    public record FriendEntry(String friendUid, String nickname, Long addTimeSec, String avatarUrl, String remark) {}

    /** profile/portrait_get 拉取的昵称与头像。 */
    public record ProfilePortrait(String nickname, String imageUrl) {}

    public record JoinedGroupEntry(
        String groupId, String groupName, String groupType, Long joinTimeSec,
        Integer memberCount, String faceUrl) {}

    public record RoamMessage(
        String fromAccount, String toAccount, String msgType, String textPreview,
        long msgTimeSec, String msgKey, String msgId, Long msgSeq) {}

    public record GroupAdminInfo(
        String groupId,
        String name,
        String ownerAccount,
        Integer memberNum,
        Integer maxMemberNum,
        Long createTimeSec,
        String faceUrl,
        String shutUpAllMember,
        String type,
        String applyJoinOption,
        String inviteJoinOption,
        String notification) {}

    public record JoinedGroupEnriched(
        String groupId,
        String groupType,
        String groupName,
        String faceUrl,
        Integer memberCount,
        String ownerUserId,
        String notice,
        long joinTimeSec,
        String imRole,
        String nameCard) {}

    /**
     * 已加入群列表查询结果。
     * {@code success=false} 表示 IM 未配置或请求失败，调用方不得据此做差集删除。
     */
    public record JoinedGroupListResult(boolean success, List<JoinedGroupEnriched> groups) {
        public static JoinedGroupListResult failed() {
            return new JoinedGroupListResult(false, List.of());
        }

        public static JoinedGroupListResult ok(List<JoinedGroupEnriched> groups) {
            return new JoinedGroupListResult(true, groups == null ? List.of() : groups);
        }
    }

    public record GroupMemberRow(
        String userUid,
        String imRole,
        Long joinTimeSec,
        String nameCard,
        Long muteUntilSec) {}

    public record MutedMemberInfo(
        String userId,
        Long muteUntilSec,
        String nameCard,
        String imRole) {}

    public record GroupSystemEvent(
        String groupId,
        String summary,
        long occurredAtSec,
        String msgType,
        String fromAccount,
        String msgKey,
        String textPreview) {}

    /** 扫描应用内群 ID（get_appid_group_list）结果。 */
    public record AppGroupIdScan(List<String> ids, int imTotal, boolean truncated) {}

    /** 扫描应用内群 ID（get_appid_group_list）。 */
    @SuppressWarnings("unchecked")
    public List<String> scanAppGroupIds(int maxIds) {
        return scanAppGroupIdsDetailed(maxIds).ids();
    }

    /** 扫描应用内群 ID，并返回 IM 侧总数与是否因上限截断。 */
    @SuppressWarnings("unchecked")
    public AppGroupIdScan scanAppGroupIdsDetailed(int maxIds) {
        if (api == null || maxIds <= 0) {
            return new AppGroupIdScan(List.of(), 0, false);
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        int imTotal = 0;
        long next = 0L;
        while (maxIds <= 0 || out.size() < maxIds) {
            Map<String, Object> body = new LinkedHashMap<>();
            int pageLimit = maxIds <= 0 ? 100 : Math.min(100, maxIds - out.size());
            body.put("Limit", pageLimit);
            body.put("Next", next);
            Map<?, ?> raw = postReturning(
                buildUrl("group_open_http_svc/get_appid_group_list"), body, "scanAppGroupIds");
            if (raw == null || !imOk(raw)) {
                break;
            }
            if (imTotal <= 0) {
                imTotal = (int) Math.min(numLong(raw.get("TotalCount")), Integer.MAX_VALUE);
            }
            Object list = raw.get("GroupIdList");
            if (!(list instanceof List<?> arr) || arr.isEmpty()) {
                break;
            }
            for (Object o : arr) {
                if (o instanceof Map<?, ?> m) {
                    String gid = str(m.get("GroupId"));
                    if (gid != null) {
                        out.add(gid);
                    }
                } else if (o != null) {
                    String gid = str(o);
                    if (gid != null) {
                        out.add(gid);
                    }
                }
                if (maxIds > 0 && out.size() >= maxIds) {
                    long nextVal = numLong(raw.get("Next"));
                    boolean truncated = nextVal != 0L
                        || (imTotal > 0 && out.size() < imTotal);
                    return new AppGroupIdScan(new ArrayList<>(out), imTotal, truncated);
                }
            }
            long nextVal = numLong(raw.get("Next"));
            if (nextVal == 0L) {
                break;
            }
            next = nextVal;
        }
        boolean truncated = imTotal > 0 && out.size() < imTotal;
        return new AppGroupIdScan(new ArrayList<>(out), imTotal, truncated);
    }

    /** 批量拉取群资料（get_group_info）。配额/传输失败时返回空 map（调用方应改用 {@link #fetchGroupAdminInfoBatch}）。 */
    public Map<String, GroupAdminInfo> fetchGroupAdminInfoMap(List<String> groupIds) {
        return fetchGroupAdminInfoBatch(groupIds).found();
    }

    public ImGroupInfoBatchResult fetchGroupAdminInfoBatch(List<String> groupIds) {
        Map<String, GroupAdminInfo> out = new LinkedHashMap<>();
        if (api == null || groupIds == null || groupIds.isEmpty()) {
            return new ImGroupInfoBatchResult(out, false, api == null);
        }
        List<String> ids = groupIds.stream()
            .filter(id -> id != null && !id.isBlank())
            .distinct()
            .toList();
        boolean rateLimited = false;
        boolean transportError = false;
        for (int i = 0; i < ids.size(); i += 50) {
            List<String> batch = ids.subList(i, Math.min(i + 50, ids.size()));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("GroupIdList", batch);
            body.put("ResponseFilter", Map.of(
                "GroupBaseInfoFilter", List.of(
                    "Type", "Name", "FaceUrl", "CreateTime", "Owner_Account",
                    "MemberNum", "MaxMemberNum", "ShutUpAllMember",
                    "ApplyJoinOption", "InviteJoinOption", "Notification")));
            Map<?, ?> raw = postReturning(
                buildUrl("group_open_http_svc/get_group_info"), body, "fetchGroupAdminInfo");
            if (raw == null) {
                transportError = true;
                continue;
            }
            if (!imOk(raw)) {
                if (isQuotaError(raw)) {
                    rateLimited = true;
                } else {
                    transportError = true;
                }
                continue;
            }
            Object list = raw.get("GroupInfo");
            if (!(list instanceof List<?> arr)) {
                continue;
            }
            for (Object o : arr) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                Object itemCode = m.get("ErrorCode");
                if (itemCode instanceof Number n && n.intValue() != 0) {
                    continue;
                }
                String gid = str(m.get("GroupId"));
                if (gid == null) {
                    continue;
                }
                long createSec = numLong(m.get("CreateTime"));
                out.put(gid, new GroupAdminInfo(
                    gid,
                    str(m.get("Name")),
                    str(m.get("Owner_Account")),
                    parseMemberNum(m),
                    parseMaxMemberNum(m),
                    createSec > 0 ? createSec : null,
                    str(m.get("FaceUrl")),
                    str(m.get("ShutUpAllMember")),
                    str(m.get("Type")),
                    str(m.get("ApplyJoinOption")),
                    str(m.get("InviteJoinOption")),
                    str(m.get("Notification"))));
            }
        }
        return new ImGroupInfoBatchResult(out, rateLimited, transportError);
    }

    public java.util.Optional<GroupAdminInfo> fetchGroupAdminInfo(String groupId) {
        ImGroupFetchResult result = fetchGroupAdminInfoResult(groupId);
        return result.isOk() ? java.util.Optional.of(result.info()) : java.util.Optional.empty();
    }

    public ImGroupFetchResult fetchGroupAdminInfoResult(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return ImGroupFetchResult.notFound(null);
        }
        if (api == null) {
            return ImGroupFetchResult.notConfigured();
        }
        String gid = groupId.trim();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("GroupIdList", List.of(gid));
        body.put("ResponseFilter", Map.of(
            "GroupBaseInfoFilter", List.of(
                "Type", "Name", "FaceUrl", "CreateTime", "Owner_Account",
                "MemberNum", "MaxMemberNum", "ShutUpAllMember",
                "ApplyJoinOption", "InviteJoinOption", "Notification")));
        Map<?, ?> raw = postReturning(
            buildUrl("group_open_http_svc/get_group_info"), body, "fetchGroupAdminInfo");
        if (raw == null) {
            return ImGroupFetchResult.error(null);
        }
        if (!imOk(raw)) {
            if (isQuotaError(raw)) {
                return ImGroupFetchResult.rateLimited(errorCodeOf(raw));
            }
            return ImGroupFetchResult.error(errorCodeOf(raw));
        }
        Object list = raw.get("GroupInfo");
        if (!(list instanceof List<?> arr) || arr.isEmpty()) {
            return ImGroupFetchResult.notFound(null);
        }
        Object first = arr.get(0);
        if (!(first instanceof Map<?, ?> m)) {
            return ImGroupFetchResult.error(null);
        }
        Object itemCode = m.get("ErrorCode");
        if (itemCode instanceof Number n && n.intValue() != 0) {
            int code = n.intValue();
            if (code == 10010 || code == 10015) {
                return ImGroupFetchResult.notFound(code);
            }
            return ImGroupFetchResult.error(code);
        }
        String id = str(m.get("GroupId"));
        if (id == null) {
            return ImGroupFetchResult.notFound(null);
        }
        long createSec = numLong(m.get("CreateTime"));
        return ImGroupFetchResult.ok(new GroupAdminInfo(
            id,
            str(m.get("Name")),
            str(m.get("Owner_Account")),
            parseMemberNum(m),
            parseMaxMemberNum(m),
            createSec > 0 ? createSec : null,
            str(m.get("FaceUrl")),
            str(m.get("ShutUpAllMember")),
            str(m.get("Type")),
            str(m.get("ApplyJoinOption")),
            str(m.get("InviteJoinOption")),
            str(m.get("Notification"))));
    }

    /** 群成员分页（get_group_member_info）。 */
    @SuppressWarnings("unchecked")
    public List<GroupMemberRow> listGroupMemberRows(String groupId, int offset, int limit) {
        if (api == null || groupId == null || groupId.isBlank() || limit <= 0) {
            return List.of();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("GroupId", groupId.trim());
        body.put("Limit", Math.min(Math.max(limit, 1), 200));
        body.put("Offset", Math.max(offset, 0));
        Map<?, ?> raw = postReturning(
            buildUrl("group_open_http_svc/get_group_member_info"), body, "listGroupMemberRows");
        if (raw == null || !imOk(raw)) {
            return List.of();
        }
        Object list = raw.get("MemberList");
        if (!(list instanceof List<?> arr)) {
            return List.of();
        }
        List<GroupMemberRow> out = new ArrayList<>();
        for (Object o : arr) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            String uid = str(m.get("Member_Account"));
            if (uid == null) {
                continue;
            }
            Object muteUntil = m.get("MutedUntil");
            long muteVal = muteUntil instanceof Number n ? n.longValue() : 0;
            out.add(new GroupMemberRow(
                uid,
                str(m.get("Role")),
                numLong(m.get("JoinTime")) > 0 ? numLong(m.get("JoinTime")) : null,
                str(m.get("NameCard")),
                muteVal));
        }
        return out;
    }

    public int countGroupMembers(String groupId) {
        java.util.Optional<GroupAdminInfo> info = fetchGroupAdminInfo(groupId);
        return info.map(i -> i.memberNum() == null ? 0 : i.memberNum()).orElse(0);
    }

    /** 查询群内当前仍处于禁言状态的成员（MutedUntil > 当前时间）。 */
    @SuppressWarnings("unchecked")
    public List<MutedMemberInfo> listMutedMembers(String groupId) {
        if (api == null || groupId == null || groupId.isBlank()) {
            return List.of();
        }
        List<MutedMemberInfo> out = new ArrayList<>();
        int offset = 0;
        int pageSize = 100;
        while (true) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("GroupId", groupId.trim());
            body.put("Limit", pageSize);
            body.put("Offset", offset);
            Map<?, ?> raw = postReturning(
                buildUrl("group_open_http_svc/get_group_member_info"), body, "listMutedMembers");
            if (raw == null || !imOk(raw)) {
                break;
            }
            Object list = raw.get("MemberList");
            if (!(list instanceof List<?> arr) || arr.isEmpty()) {
                break;
            }
            boolean hasMore = arr.size() == pageSize;
            for (Object o : arr) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                Object muteUntil = m.get("MutedUntil");
                long muteVal = muteUntil instanceof Number n ? n.longValue() : 0;
                java.util.Optional<Long> activeUntil = activeMuteUntilSec(muteVal);
                if (activeUntil.isEmpty()) {
                    continue;
                }
                String uid = str(m.get("Member_Account"));
                if (uid == null) {
                    continue;
                }
                out.add(new MutedMemberInfo(
                    uid,
                    activeUntil.get(),
                    str(m.get("NameCard")),
                    str(m.get("Role"))));
            }
            if (!hasMore) {
                break;
            }
            offset += pageSize;
        }
        return out;
    }

    /** 拉取群内系统/通知类漫游消息，用于操作日志。 */
    public List<GroupSystemEvent> listGroupSystemEvents(String groupId, int maxMessages) {
        if (groupId == null || groupId.isBlank()) {
            return List.of();
        }
        List<RoamMessage> msgs = getGroupMessages(groupId.trim(), Math.min(Math.max(maxMessages, 1), 100), null);
        List<GroupSystemEvent> out = new ArrayList<>();
        for (RoamMessage m : msgs) {
            if (!isGroupSystemMessage(m)) {
                continue;
            }
            String summary = buildSystemSummary(m);
            out.add(new GroupSystemEvent(
                groupId.trim(),
                summary,
                m.msgTimeSec(),
                m.msgType(),
                m.fromAccount(),
                m.msgKey(),
                m.textPreview()));
        }
        return out;
    }

    private static boolean isGroupSystemMessage(RoamMessage m) {
        if (m == null) {
            return false;
        }
        String from = m.fromAccount() == null ? "" : m.fromAccount().trim();
        if (from.isEmpty() || from.startsWith("@TIM") || "administrator".equalsIgnoreCase(from)) {
            return true;
        }
        String type = m.msgType() == null ? "" : m.msgType();
        return type.contains("Group") || "TIMCustomElem".equals(type);
    }

    private static String buildSystemSummary(RoamMessage m) {
        if (m.textPreview() != null && !m.textPreview().isBlank()) {
            return m.textPreview();
        }
        if (m.msgType() != null && !m.msgType().isBlank()) {
            return m.msgType();
        }
        return "系统消息";
    }

    private static Integer parseMaxMemberNum(Map<?, ?> m) {
        Object v = m.get("MaxMemberNum");
        if (v instanceof Number n) {
            int count = n.intValue();
            return count >= 0 ? count : null;
        }
        return null;
    }

    /** 好友列表（sns/friend_get），最多拉取 maxCount 条。 */
    @SuppressWarnings("unchecked")
    public List<FriendEntry> listFriends(String userId, int maxCount) {
        if (api == null || userId == null || userId.isBlank() || maxCount <= 0) {
            return List.of();
        }
        List<FriendEntry> out = new ArrayList<>();
        int startIndex = 0;
        int standardSequence = 0;
        while (out.size() < maxCount) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("From_Account", userId);
            body.put("StartIndex", startIndex);
            body.put("StandardSequence", standardSequence);
            Map<?, ?> raw = postReturning(buildUrl("sns/friend_get"), body, "listFriends user=" + userId);
            if (raw == null || !imOk(raw)) {
                break;
            }
            List<?> arr = friendListArray(raw);
            if (arr.isEmpty()) {
                break;
            }
            for (Object o : arr) {
                if (out.size() >= maxCount) {
                    break;
                }
                FriendEntry parsed = parseFriendEntry(o);
                if (parsed != null) {
                    out.add(parsed);
                }
            }
            Object complete = raw.get("CompleteFlag");
            if (complete instanceof Number c && c.intValue() == 1) {
                break;
            }
            Object next = raw.get("NextStartIndex");
            Object seq = raw.get("StandardSequence");
            if (seq instanceof Number sn) {
                standardSequence = sn.intValue();
            }
            if (!(next instanceof Number n) || n.intValue() <= startIndex) {
                break;
            }
            startIndex = n.intValue();
        }
        if (!out.isEmpty()) {
            out = enrichFriendEntries(out);
        }
        return out;
    }

    private List<FriendEntry> enrichFriendEntries(List<FriendEntry> entries) {
        List<String> ids = entries.stream().map(FriendEntry::friendUid).toList();
        Map<String, ProfilePortrait> profiles = getPortraitProfiles(ids);
        return entries.stream()
            .map(f -> {
                ProfilePortrait p = profiles.get(f.friendUid());
                String nick = f.nickname();
                String avatar = f.avatarUrl();
                if (p != null) {
                    if ((nick == null || nick.isBlank()) && p.nickname() != null) {
                        nick = p.nickname();
                    }
                    if ((avatar == null || avatar.isBlank()) && p.imageUrl() != null) {
                        avatar = p.imageUrl();
                    }
                }
                return new FriendEntry(f.friendUid(), nick, f.addTimeSec(), avatar, f.remark());
            })
            .toList();
    }

    /**
     * 从 owner 的好友关系中解析 peer 展示名：备注名 {@code Tag_SNS_IM_Remark} 优先，
     * 其次好友昵称 / 资料昵称。非好友或 IM 未配置时返回 empty。
     */
    public java.util.Optional<String> resolveFriendDisplayName(String ownerUserId, String friendUserId) {
        if (api == null || ownerUserId == null || ownerUserId.isBlank()
            || friendUserId == null || friendUserId.isBlank()) {
            return java.util.Optional.empty();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("From_Account", ownerUserId.trim());
        body.put("To_Account", List.of(friendUserId.trim()));
        body.put("TagList", List.of(
            "Tag_SNS_IM_Remark",
            "Tag_SNS_IM_Nick",
            "Tag_Profile_IM_Nick"));
        Map<?, ?> raw = postReturning(buildUrl("sns/friend_get_list"), body,
            "friendDisplayName owner=" + ownerUserId + " friend=" + friendUserId);
        if (raw == null || !imOk(raw)) {
            return java.util.Optional.empty();
        }
        Object items = raw.get("InfoItem");
        if (!(items instanceof List<?> arr) || arr.isEmpty()) {
            return java.util.Optional.empty();
        }
        for (Object item : arr) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            Object resultCode = m.get("ResultCode");
            if (resultCode instanceof Number code && code.intValue() != 0) {
                continue;
            }
            String displayName = parseFriendDisplayName(m);
            if (displayName != null && !displayName.isBlank()) {
                return java.util.Optional.of(displayName);
            }
        }
        return java.util.Optional.empty();
    }

    private static String parseFriendDisplayName(Map<?, ?> item) {
        String remark = null;
        String snsNick = null;
        String profileNick = null;
        Object snsProfile = item.get("SnsProfileItem");
        if (snsProfile instanceof List<?> profiles) {
            for (Object pi : profiles) {
                if (!(pi instanceof Map<?, ?> tagEntry)) {
                    continue;
                }
                String tag = str(tagEntry.get("Tag"));
                String text = trimTagValue(tagEntry.get("Value"));
                if (text == null) {
                    continue;
                }
                switch (tag) {
                    case "Tag_SNS_IM_Remark" -> remark = text;
                    case "Tag_SNS_IM_Nick" -> snsNick = text;
                    case "Tag_Profile_IM_Nick" -> profileNick = text;
                    default -> { }
                }
            }
        }
        Object valueItems = item.get("ValueItem");
        if (valueItems instanceof List<?> vals) {
            for (Object vi : vals) {
                if (!(vi instanceof Map<?, ?> tagEntry)) {
                    continue;
                }
                String tag = str(tagEntry.get("Tag"));
                String text = trimTagValue(tagEntry.get("Value"));
                if (text == null) {
                    continue;
                }
                switch (tag) {
                    case "Tag_SNS_IM_Remark" -> remark = text;
                    case "Tag_SNS_IM_Nick" -> {
                        if (snsNick == null) {
                            snsNick = text;
                        }
                    }
                    case "Tag_Profile_IM_Nick" -> {
                        if (profileNick == null) {
                            profileNick = text;
                        }
                    }
                    default -> { }
                }
            }
        }
        if (remark != null && !remark.isBlank()) {
            return remark;
        }
        if (snsNick != null && !snsNick.isBlank()) {
            return snsNick;
        }
        if (profileNick != null && !profileNick.isBlank()) {
            return profileNick;
        }
        return null;
    }

    private static String trimTagValue(Object val) {
        if (val == null) {
            return null;
        }
        String text = val.toString().trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * 批量拉取 IM 资料昵称与头像（profile/portrait_get）。
     * 单次最多 100 个账号；未配置 IM 或失败时返回空 Map。
     */
    public Map<String, ProfilePortrait> getPortraitProfiles(Collection<String> userIds) {
        if (api == null || userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<String> ids = userIds.stream()
            .filter(id -> id != null && !id.isBlank())
            .distinct()
            .limit(100)
            .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("To_Account", ids);
            body.put("TagList", List.of("Tag_Profile_IM_Nick", "Tag_Profile_IM_Image"));
            Map<?, ?> raw = postReturning(buildUrl("profile/portrait_get"), body, "portraitGet count=" + ids.size());
            if (raw == null || !imOk(raw)) {
                return Map.of();
            }
            return parsePortraitProfiles(raw.get("UserProfileItem"));
        } catch (Exception e) {
            log.warn("getPortraitProfiles failed {}", e.getMessage());
            return Map.of();
        }
    }

    private Map<String, ProfilePortrait> parsePortraitProfiles(Object profileItems) {
        Map<String, ProfilePortrait> out = new HashMap<>();
        if (!(profileItems instanceof List<?> arr)) {
            return out;
        }
        for (Object item : arr) {
            if (!(item instanceof Map<?, ?> entry)) {
                continue;
            }
            Object account = entry.get("To_Account");
            Object resultCode = entry.get("ResultCode");
            if (account == null) {
                continue;
            }
            if (resultCode instanceof Number n && n.intValue() != 0) {
                continue;
            }
            String userId = account.toString();
            String nick = null;
            String image = null;
            Object tags = entry.get("ProfileItem");
            if (tags instanceof List<?> tagList) {
                for (Object tagObj : tagList) {
                    if (!(tagObj instanceof Map<?, ?> tagEntry)) {
                        continue;
                    }
                    String tag = str(tagEntry.get("Tag"));
                    Object value = tagEntry.get("Value");
                    if (value == null || value.toString().isBlank()) {
                        continue;
                    }
                    String v = value.toString().trim();
                    if ("Tag_Profile_IM_Nick".equals(tag)) {
                        nick = v;
                    } else if ("Tag_Profile_IM_Image".equals(tag)) {
                        image = v;
                    }
                }
            }
            out.put(userId, new ProfilePortrait(nick, image));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<?> friendListArray(Map<?, ?> raw) {
        Object items = raw.get("UserDataItem");
        if (items instanceof List<?> arr && !arr.isEmpty()) {
            return arr;
        }
        items = raw.get("InfoItem");
        if (items instanceof List<?> arr && !arr.isEmpty()) {
            return arr;
        }
        return List.of();
    }

    private static FriendEntry parseFriendEntry(Object o) {
        if (!(o instanceof Map<?, ?> m)) {
            return null;
        }
        Object to = m.get("To_Account");
        if (to == null || to.toString().isBlank()) {
            return null;
        }
        String friendUid = to.toString().trim();
        long addSec = 0;
        Object addTime = m.get("AddTime");
        if (addTime instanceof Number n) {
            addSec = n.longValue();
        }
        String remark = null;
        String snsNick = null;
        String profileNick = null;
        Object valueItems = m.get("ValueItem");
        if (valueItems instanceof List<?> vals) {
            for (Object vi : vals) {
                if (!(vi instanceof Map<?, ?> tagEntry)) {
                    continue;
                }
                String tag = str(tagEntry.get("Tag"));
                Object val = tagEntry.get("Value");
                if ("Tag_SNS_IM_AddTime".equals(tag) && addSec == 0) {
                    addSec = parseLongValue(val);
                } else {
                    String text = trimTagValue(val);
                    if (text == null) {
                        continue;
                    }
                    switch (tag) {
                        case "Tag_SNS_IM_Remark" -> remark = text;
                        case "Tag_SNS_IM_Nick" -> snsNick = text;
                        case "Tag_Profile_IM_Nick" -> profileNick = text;
                        default -> { }
                    }
                }
            }
        }
        Object snsProfile = m.get("SnsProfileItem");
        if (snsProfile instanceof List<?> profiles) {
            for (Object pi : profiles) {
                if (!(pi instanceof Map<?, ?> tagEntry)) {
                    continue;
                }
                String tag = str(tagEntry.get("Tag"));
                String text = trimTagValue(tagEntry.get("Value"));
                if (text == null) {
                    continue;
                }
                switch (tag) {
                    case "Tag_SNS_IM_Remark" -> remark = text;
                    case "Tag_SNS_IM_Nick" -> {
                        if (snsNick == null) {
                            snsNick = text;
                        }
                    }
                    case "Tag_Profile_IM_Nick" -> {
                        if (profileNick == null) {
                            profileNick = text;
                        }
                    }
                    default -> { }
                }
            }
        }
        String nickname = snsNick != null ? snsNick : profileNick;
        return new FriendEntry(friendUid, nickname, addSec, null, remark);
    }

    private static long parseLongValue(Object val) {
        if (val instanceof Number n) {
            return n.longValue();
        }
        if (val != null) {
            try {
                return Long.parseLong(val.toString().trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    /** 已加入群列表（get_joined_group_list + get_group_info 补全名称）。 */
    @SuppressWarnings("unchecked")
    public List<JoinedGroupEntry> listJoinedGroups(String userId, int offset, int limit) {
        if (api == null || userId == null || userId.isBlank() || limit <= 0) {
            return List.of();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Member_Account", userId);
        body.put("Limit", Math.min(limit, 5000));
        body.put("Offset", Math.max(offset, 0));
        body.put("GroupType", "");
        body.put("ResponseFilter", Map.of(
            "GroupBaseInfoFilter", List.of("Name", "FaceUrl", "MemberNum"),
            "SelfInfoFilter", List.of("JoinTime", "Role")));
        Map<?, ?> raw = postReturning(
            buildUrl("group_open_http_svc/get_joined_group_list"), body,
            "listJoinedGroups user=" + userId);
        if (raw == null || !imOk(raw)) {
            return List.of();
        }
        Object list = raw.get("GroupIdList");
        if (!(list instanceof List<?> arr)) {
            return List.of();
        }
        List<JoinedGroupEntry> out = new ArrayList<>();
        for (Object o : arr) {
            if (out.size() >= limit) {
                break;
            }
            if (o instanceof Map<?, ?> m) {
                String gid = str(m.get("GroupId"));
                if (gid == null) {
                    continue;
                }
                out.add(new JoinedGroupEntry(
                    gid,
                    str(m.get("Name")),
                    parseSelfMemberRole(m),
                    parseSelfJoinTime(m),
                    parseMemberNum(m),
                    str(m.get("FaceUrl"))));
            } else if (o != null) {
                String gid = o.toString().trim();
                if (!gid.isEmpty()) {
                    out.add(new JoinedGroupEntry(gid, null, null, 0L, null, null));
                }
            }
        }
        if (!out.isEmpty()) {
            List<String> allIds = out.stream().map(JoinedGroupEntry::groupId).toList();
            Map<String, JoinedGroupEntry> enriched = fetchGroupInfoMap(allIds);
            out = out.stream()
                .map(g -> {
                    JoinedGroupEntry info = enriched.get(g.groupId());
                    if (info == null) {
                        return g;
                    }
                    Integer memberCount = g.memberCount() != null ? g.memberCount() : info.memberCount();
                    return new JoinedGroupEntry(
                        g.groupId(),
                        info.groupName() != null ? info.groupName() : g.groupName(),
                        g.groupType(),
                        g.joinTimeSec() > 0 ? g.joinTimeSec() : info.joinTimeSec(),
                        memberCount,
                        info.faceUrl() != null ? info.faceUrl() : g.faceUrl());
                })
                .toList();
            out = fillMissingMemberRoles(userId, out);
        }
        return out;
    }

    /** 已加入群列表（含 Type/Notification/NameCard 等，供群资料投影同步）。 */
    @SuppressWarnings("unchecked")
    public JoinedGroupListResult listJoinedGroupsEnriched(String userId, int offset, int limit) {
        if (api == null || userId == null || userId.isBlank() || limit <= 0) {
            return JoinedGroupListResult.failed();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Member_Account", userId);
        body.put("Limit", Math.min(limit, 5000));
        body.put("Offset", Math.max(offset, 0));
        body.put("GroupType", "");
        body.put("ResponseFilter", Map.of(
            "GroupBaseInfoFilter", List.of(
                "Type", "Name", "FaceUrl", "MemberNum", "Owner_Account", "Notification"),
            "SelfInfoFilter", List.of("JoinTime", "Role", "NameCard")));
        Map<?, ?> raw = postReturning(
            buildUrl("group_open_http_svc/get_joined_group_list"), body,
            "listJoinedGroupsEnriched user=" + userId);
        if (raw == null || !imOk(raw)) {
            return JoinedGroupListResult.failed();
        }
        Object list = raw.get("GroupIdList");
        if (!(list instanceof List<?> arr)) {
            return JoinedGroupListResult.ok(List.of());
        }
        List<JoinedGroupEnriched> out = new ArrayList<>();
        for (Object o : arr) {
            if (out.size() >= limit) {
                break;
            }
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            String gid = str(m.get("GroupId"));
            if (gid == null) {
                continue;
            }
            String imRole = parseSelfImRole(m);
            // 缺 Role 时不再同步连环 getRoleInGroup（会打爆 60007）；投影侧默认 Member，异步队列回源校正
            out.add(new JoinedGroupEnriched(
                gid,
                str(m.get("Type")),
                str(m.get("Name")),
                str(m.get("FaceUrl")),
                parseMemberNum(m),
                str(m.get("Owner_Account")),
                str(m.get("Notification")),
                parseSelfJoinTime(m),
                imRole,
                parseSelfNameCard(m)));
        }
        return JoinedGroupListResult.ok(out);
    }

    private static String parseSelfImRole(Map<?, ?> groupItem) {
        Object self = groupItem.get("SelfInfo");
        if (self instanceof Map<?, ?> si) {
            return str(si.get("Role"));
        }
        return null;
    }

    private static String parseSelfNameCard(Map<?, ?> groupItem) {
        Object self = groupItem.get("SelfInfo");
        if (self instanceof Map<?, ?> si) {
            return str(si.get("NameCard"));
        }
        return null;
    }

    /** 将 IM 群内角色（Owner/Admin/Member）映射为运营展示文案。 */
    public static String mapMemberRoleLabel(String imRole) {
        if (imRole == null || imRole.isBlank()) {
            return null;
        }
        return switch (imRole.trim()) {
            case "Owner" -> "群主";
            case "Admin" -> "管理员";
            case "Member" -> "普通成员";
            default -> null;
        };
    }

    private List<JoinedGroupEntry> fillMissingMemberRoles(String userId, List<JoinedGroupEntry> entries) {
        // 历史逻辑对缺 Role 的条目同步 getRoleInGroup，已改为不再回源，避免尖峰打爆配额。
        return entries;
    }

    public int joinedGroupTotal(String userId) {
        return joinedGroupCount(userId);
    }

    /**
     * 统计用户作为群主拥有的指定类型群数量（Work / Community）。
     * IM 未配置或失败时返回 0。
     */
    public int countOwnedGroupsByType(String userId, String groupType) {
        if (groupType == null || groupType.isBlank()) {
            return 0;
        }
        return countOwnedGroupsByTypes(userId, List.of(groupType)).getOrDefault(groupType, 0);
    }

    /**
     * 同一次请求内复用 joined list + group info：对多个类型各计一次 Owner 数。
     * 返回 map 的 key 为入参类型原样（大小写与入参一致）；查不到的类型值为 0。
     */
    public Map<String, Integer> countOwnedGroupsByTypes(String userId, Collection<String> groupTypes) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (groupTypes == null || groupTypes.isEmpty()) {
            return result;
        }
        List<String> types = groupTypes.stream()
            .filter(t -> t != null && !t.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
        for (String type : types) {
            result.put(type, 0);
        }
        if (api == null || userId == null || userId.isBlank() || types.isEmpty()) {
            return result;
        }
        try {
            List<String> ownedIds = listOwnedGroupIds(userId);
            if (ownedIds.isEmpty()) {
                return result;
            }
            Map<String, GroupAdminInfo> infos = fetchGroupAdminInfoMap(ownedIds);
            Map<String, Integer> countsByLower = new HashMap<>();
            for (String type : types) {
                countsByLower.put(type.toLowerCase(), 0);
            }
            for (String gid : ownedIds) {
                GroupAdminInfo info = infos.get(gid);
                if (info == null || info.type() == null) {
                    continue;
                }
                if (!userId.equals(info.ownerAccount())) {
                    continue;
                }
                String key = info.type().toLowerCase();
                if (countsByLower.containsKey(key)) {
                    countsByLower.put(key, countsByLower.get(key) + 1);
                }
            }
            for (String type : types) {
                result.put(type, countsByLower.getOrDefault(type.toLowerCase(), 0));
            }
            return result;
        } catch (Exception e) {
            log.warn("countOwnedGroupsByTypes failed userId={} types={} {}", userId, types, e.getMessage());
            return result;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> listOwnedGroupIds(String userId) {
        List<String> out = new ArrayList<>();
        int offset = 0;
        while (true) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("Member_Account", userId);
            body.put("Limit", 100);
            body.put("Offset", offset);
            body.put("GroupType", "");
            body.put("ResponseFilter", Map.of("SelfInfoFilter", List.of("Role")));
            Map<?, ?> raw = postReturning(
                buildUrl("group_open_http_svc/get_joined_group_list"), body,
                "listOwnedGroupIds user=" + userId);
            if (raw == null || !imOk(raw)) {
                break;
            }
            Object list = raw.get("GroupIdList");
            if (!(list instanceof List<?> arr) || arr.isEmpty()) {
                break;
            }
            for (Object o : arr) {
                if (o instanceof Map<?, ?> m) {
                    if ("Owner".equalsIgnoreCase(parseRawSelfRole(m))) {
                        String gid = str(m.get("GroupId"));
                        if (gid != null) {
                            out.add(gid);
                        }
                    }
                }
            }
            if (arr.size() < 100) {
                break;
            }
            offset += arr.size();
        }
        return out;
    }

    private static String parseRawSelfRole(Map<?, ?> groupItem) {
        Object self = groupItem.get("SelfInfo");
        if (self instanceof Map<?, ?> si) {
            return str(si.get("Role"));
        }
        return null;
    }

    /** 管理员撤回单聊消息（openim/admin_msgwithdraw）。 */
    public void adminRecallC2cMessage(String fromAccount, String toAccount, String msgKey) {
        if (fromAccount == null || fromAccount.isBlank()
            || toAccount == null || toAccount.isBlank()
            || msgKey == null || msgKey.isBlank()) {
            throw new ImRestException("INVALID_INPUT", 0);
        }
        Map<String, Object> body = Map.of(
            "From_Account", fromAccount,
            "To_Account", toAccount,
            "MsgKey", msgKey);
        postRequired("openim/admin_msgwithdraw", body,
            "adminRecallC2c " + fromAccount + "->" + toAccount + " key=" + msgKey);
    }

    /** 管理员撤回群消息（group_open_http_svc/group_msg_recall），单次最多 10 条 MsgSeq。 */
    public Map<String, Object> adminRecallGroupMessages(String groupId,
                                                        List<Long> msgSeqList,
                                                        String reason) {
        if (groupId == null || groupId.isBlank() || msgSeqList == null || msgSeqList.isEmpty()) {
            throw new ImRestException("INVALID_INPUT", 0);
        }
        List<Map<String, Object>> seqList = new ArrayList<>();
        for (Long seq : msgSeqList) {
            if (seq == null || seq <= 0) {
                throw new ImRestException("INVALID_INPUT", 0);
            }
            seqList.add(Map.of("MsgSeq", seq));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("GroupId", groupId);
        body.put("MsgSeqList", seqList);
        if (reason != null && !reason.isBlank()) {
            body.put("Reason", reason.trim());
        }
        return postRequired("group_open_http_svc/group_msg_recall", body,
            "adminRecallGroup group=" + groupId + " seqs=" + msgSeqList.size());
    }

    /** C2C 漫游消息（openim/admin_getroammsg）。 */
    @SuppressWarnings("unchecked")
    public List<RoamMessage> adminGetRoamMessages(
        String userA, String userB, int maxCnt, Long minTimeSec, Long maxTimeSec, String lastMsgKey) {
        if (api == null || userA == null || userB == null) {
            return List.of();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Operator_Account", userA);
        body.put("Peer_Account", userB);
        body.put("MaxCnt", Math.min(Math.max(maxCnt, 1), 100));
        long nowSec = System.currentTimeMillis() / 1000L;
        body.put("MinTime", minTimeSec == null ? 0L : minTimeSec);
        // MaxTime 须为合法 Unix 秒（≤ uint32）；9999999999 会导致 IM 90001 并静默返回空列表
        body.put("MaxTime", maxTimeSec == null ? nowSec : maxTimeSec);
        if (lastMsgKey != null && !lastMsgKey.isBlank()) {
            body.put("LastMsgKey", lastMsgKey);
        }
        Map<?, ?> raw = postReturning(buildUrl("openim/admin_getroammsg"), body,
            "adminGetRoamMsg " + userA + "-" + userB);
        return parseRoamMsgList(raw);
    }

    /**
     * 双向拉取 C2C 漫游并合并（服务端发消息 SyncOtherMachine=2 时仅出现在接收方漫游）。
     */
    public List<RoamMessage> adminGetRoamMessagesMerged(
        String userA, String userB, int maxCnt, String lastMsgKey) {
        if (api == null || userA == null || userB == null) {
            return List.of();
        }
        int limit = Math.min(Math.max(maxCnt, 1), 100);
        long nowSec = System.currentTimeMillis() / 1000L;
        long maxTimeSec = nowSec;
        if (lastMsgKey != null && !lastMsgKey.isBlank()) {
            long cursor = parseMsgKeyTimestamp(lastMsgKey);
            if (cursor > 0) {
                maxTimeSec = cursor;
            }
        }
        List<RoamMessage> fromA = adminGetRoamMessages(
            userA, userB, limit, 0L, maxTimeSec, lastMsgKey);
        List<RoamMessage> fromB = adminGetRoamMessages(
            userB, userA, limit, 0L, maxTimeSec, lastMsgKey);
        Map<String, RoamMessage> byKey = new LinkedHashMap<>();
        for (RoamMessage m : fromA) {
            if (m.msgKey() != null && !m.msgKey().isBlank()) {
                byKey.putIfAbsent(m.msgKey(), m);
            }
        }
        for (RoamMessage m : fromB) {
            if (m.msgKey() != null && !m.msgKey().isBlank()) {
                byKey.putIfAbsent(m.msgKey(), m);
            }
        }
        return byKey.values().stream()
            .sorted(java.util.Comparator.comparingLong(RoamMessage::msgTimeSec).reversed())
            .limit(limit)
            .toList();
    }

    private static long parseMsgKeyTimestamp(String msgKey) {
        if (msgKey == null || msgKey.isBlank()) {
            return 0L;
        }
        int idx = msgKey.lastIndexOf('_');
        if (idx < 0 || idx >= msgKey.length() - 1) {
            return 0L;
        }
        try {
            return Long.parseLong(msgKey.substring(idx + 1));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 群漫游消息（group_msg_get_simple）。 */
    @SuppressWarnings("unchecked")
    public List<RoamMessage> getGroupMessages(String groupId, int reqMsgNumber, Long reqMsgSeq) {
        if (api == null || groupId == null || groupId.isBlank()) {
            return List.of();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("GroupId", groupId);
        body.put("ReqMsgNumber", Math.min(Math.max(reqMsgNumber, 1), 100));
        if (reqMsgSeq != null) {
            body.put("ReqMsgSeq", reqMsgSeq);
        }
        Map<?, ?> raw = postReturning(buildUrl("group_open_http_svc/group_msg_get_simple"), body,
            "groupMsgGet group=" + groupId);
        return parseGroupMsgList(raw, groupId);
    }

    private Map<String, JoinedGroupEntry> fetchGroupInfoMap(List<String> groupIds) {
        Map<String, JoinedGroupEntry> out = new HashMap<>();
        if (groupIds == null || groupIds.isEmpty()) {
            return out;
        }
        List<String> ids = groupIds.stream()
            .filter(id -> id != null && !id.isBlank())
            .distinct()
            .toList();
        for (int i = 0; i < ids.size(); i += 50) {
            List<String> batch = ids.subList(i, Math.min(i + 50, ids.size()));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("GroupIdList", batch);
            body.put("ResponseFilter", Map.of(
                "GroupBaseInfoFilter", List.of("Name", "FaceUrl", "MemberNum")));
            Map<?, ?> raw = postReturning(buildUrl("group_open_http_svc/get_group_info"), body, "getGroupInfo");
            if (raw == null || !imOk(raw)) {
                continue;
            }
            Object list = raw.get("GroupInfo");
            if (!(list instanceof List<?> arr)) {
                continue;
            }
            for (Object o : arr) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                Object itemCode = m.get("ErrorCode");
                if (itemCode instanceof Number n && n.intValue() != 0) {
                    continue;
                }
                String gid = str(m.get("GroupId"));
                if (gid == null) {
                    continue;
                }
                out.put(gid, new JoinedGroupEntry(
                    gid, str(m.get("Name")), null, 0L, parseMemberNum(m), str(m.get("FaceUrl"))));
            }
        }
        return out;
    }

    private static long parseSelfJoinTime(Map<?, ?> groupItem) {
        Object self = groupItem.get("SelfInfo");
        if (self instanceof Map<?, ?> si) {
            long t = numLong(si.get("JoinTime"));
            if (t > 0) {
                return t;
            }
        }
        return numLong(groupItem.get("JoinTime"));
    }

    private static String parseSelfMemberRole(Map<?, ?> groupItem) {
        Object self = groupItem.get("SelfInfo");
        if (self instanceof Map<?, ?> si) {
            return mapMemberRoleLabel(str(si.get("Role")));
        }
        return null;
    }

    private static Integer parseMemberNum(Map<?, ?> groupItem) {
        Object v = groupItem.get("MemberNum");
        if (v instanceof Number n) {
            int count = n.intValue();
            return count >= 0 ? count : null;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<RoamMessage> parseRoamMsgList(Map<?, ?> raw) {
        if (raw == null) {
            return List.of();
        }
        if (!imOk(raw)) {
            log.warn("adminGetRoamMsg IM error code={} info={}", raw.get("ErrorCode"), raw.get("ErrorInfo"));
            return List.of();
        }
        Object list = raw.get("MsgList");
        if (!(list instanceof List<?> arr)) {
            return List.of();
        }
        List<RoamMessage> out = new ArrayList<>();
        for (Object o : arr) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            String from = str(m.get("From_Account"));
            String to = str(m.get("To_Account"));
            long ts = numLong(m.get("MsgTimeStamp"));
            String key = str(m.get("MsgKey"));
            String msgId = str(m.get("MsgId"));
            String[] preview = extractMsgPreview(m.get("MsgBody"));
            out.add(new RoamMessage(from, to, preview[0], preview[1], ts, key, msgId, null));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<RoamMessage> parseGroupMsgList(Map<?, ?> raw, String groupId) {
        if (raw == null || !imOk(raw)) {
            return List.of();
        }
        Object list = raw.get("RspMsgList");
        if (!(list instanceof List<?> arr)) {
            return List.of();
        }
        List<RoamMessage> out = new ArrayList<>();
        for (Object o : arr) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            String from = str(m.get("From_Account"));
            long ts = numLong(m.get("MsgTimeStamp"));
            String key = str(m.get("MsgKey"));
            String msgId = str(m.get("MsgId"));
            Long msgSeq = parseMsgSeq(m.get("MsgSeq"));
            String[] preview = extractMsgPreview(m.get("MsgBody"));
            out.add(new RoamMessage(from, groupId, preview[0], preview[1], ts, key, msgId, msgSeq));
        }
        return out;
    }

    private static Long parseMsgSeq(Object raw) {
        if (raw instanceof Number n) {
            long v = n.longValue();
            return v > 0 ? v : null;
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                long v = Long.parseLong(s.trim());
                return v > 0 ? v : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String[] extractMsgPreview(Object msgBody) {
        if (!(msgBody instanceof List<?> arr) || arr.isEmpty()) {
            return new String[] {"UNKNOWN", ""};
        }
        Object first = arr.get(0);
        if (!(first instanceof Map<?, ?> m)) {
            return new String[] {"UNKNOWN", ""};
        }
        String type = str(m.get("MsgType"));
        Object content = m.get("MsgContent");
        String text = "";
        if (content instanceof Map<?, ?> cm) {
            Object t = cm.get("Text");
            if (t != null) {
                text = t.toString();
            }
        } else if (content != null) {
            text = content.toString();
        }
        if (text.length() > 500) {
            text = text.substring(0, 500);
        }
        return new String[] {type == null ? "UNKNOWN" : type, text};
    }

    private static boolean imOk(Map<?, ?> raw) {
        Object code = raw.get("ErrorCode");
        return code instanceof Number n && n.intValue() == 0;
    }

    private static boolean isQuotaError(Map<?, ?> raw) {
        Object code = raw.get("ErrorCode");
        if (code instanceof Number n && n.intValue() == 60007) {
            return true;
        }
        Object info = raw.get("ErrorInfo");
        return info != null && info.toString().toLowerCase().contains("blocked by quota");
    }

    private static Integer errorCodeOf(Map<?, ?> raw) {
        Object code = raw.get("ErrorCode");
        return code instanceof Number n ? n.intValue() : null;
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static long numLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static int parseInt(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    /** 已加入群数量（get_joined_group_list）；IM 未配置或失败时返回 0。 */
    public int joinedGroupCount(String userId) {
        if (api == null || userId == null || userId.isBlank()) {
            return 0;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("Member_Account", userId);
            body.put("Limit", 5000);
            body.put("Offset", 0);
            body.put("GroupType", "");
            Map<?, ?> raw = postReturning(
                buildUrl("group_open_http_svc/get_joined_group_list"), body,
                "joinedGroupCount user=" + userId);
            if (raw == null) {
                return 0;
            }
            Object code = raw.get("ErrorCode");
            if (code instanceof Number n && n.intValue() != 0) {
                return 0;
            }
            Object total = raw.get("TotalCount");
            if (total instanceof Number num) {
                return num.intValue();
            }
            Object list = raw.get("GroupIdList");
            if (list instanceof List<?> arr) {
                return arr.size();
            }
            return 0;
        } catch (Exception e) {
            log.warn("joinedGroupCount failed userId={} {}", userId, e.getMessage());
            return 0;
        }
    }

    public void setCallCallback(String url, List<String> callbackCommands) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Url", url);
        body.put("CallbackCommandList", callbackCommands);
        postRequired("call_config/set_callback", body, "setCallCallback");
    }

    public record GroupBaseInfo(String name, String faceUrl) {}

    /** 群名称与头像（get_group_info）。 */
    public java.util.Optional<GroupBaseInfo> getGroupBaseInfo(String groupId) {
        if (api == null || groupId == null || groupId.isBlank()) {
            return java.util.Optional.empty();
        }
        Map<String, Object> body = Map.of(
            "GroupIdList", List.of(groupId),
            "ResponseFilter", Map.of("GroupBaseInfoFilter", List.of("Name", "FaceUrl")));
        Map<?, ?> raw = postReturning(buildUrl("group_open_http_svc/get_group_info"), body, "getGroupBaseInfo");
        if (raw == null || !imOk(raw)) {
            return java.util.Optional.empty();
        }
        Object list = raw.get("GroupInfo");
        if (!(list instanceof List<?> arr) || arr.isEmpty()) {
            return java.util.Optional.empty();
        }
        Object first = arr.get(0);
        if (!(first instanceof Map<?, ?> m)) {
            return java.util.Optional.empty();
        }
        Object itemCode = m.get("ErrorCode");
        if (itemCode instanceof Number n && n.intValue() != 0) {
            return java.util.Optional.empty();
        }
        String name = str(m.get("Name"));
        String faceUrl = str(m.get("FaceUrl"));
        if ((name == null || name.isBlank()) && (faceUrl == null || faceUrl.isBlank())) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new GroupBaseInfo(
            name == null ? null : name.trim(),
            faceUrl == null ? null : faceUrl.trim()));
    }

    /** 群名称（get_group_info）。 */
    public java.util.Optional<String> getGroupName(String groupId) {
        return getGroupBaseInfo(groupId)
            .map(GroupBaseInfo::name)
            .filter(name -> name != null && !name.isBlank());
    }

    public record AddGroupMemberResult(String memberAccount, int result, String resultInfo) {}

    /**
     * 创建群（create_group）。返回 IM 分配的 groupId。
     */
    public String createGroup(String ownerUserId,
                              String groupType,
                              String groupName,
                              String faceUrl,
                              String introduction,
                              List<String> memberUserIds,
                              GroupJoinOption applyJoinOption,
                              GroupJoinOption inviteJoinOption) {
        if (api == null) {
            throw new ImRestException("IM_NOT_CONFIGURED", 0);
        }
        if (ownerUserId == null || ownerUserId.isBlank()
            || groupType == null || groupType.isBlank()
            || groupName == null || groupName.isBlank()) {
            throw new ImRestException("INVALID_INPUT", 0);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Owner_Account", ownerUserId.trim());
        body.put("Type", groupType.trim());
        body.put("Name", groupName.trim());
        if (faceUrl != null && !faceUrl.isBlank()) {
            body.put("FaceUrl", faceUrl.trim());
        }
        if (introduction != null && !introduction.isBlank()) {
            body.put("Introduction", introduction.trim());
        }
        GroupJoinOption apply = applyJoinOption == null ? GroupJoinOption.need_permission : applyJoinOption;
        GroupJoinOption invite = inviteJoinOption == null ? GroupJoinOption.need_permission : inviteJoinOption;
        body.put("ApplyJoinOption", apply.imApplyValue());
        body.put("InviteJoinOption", invite.imInviteValue());
        if (memberUserIds != null && !memberUserIds.isEmpty()) {
            List<Map<String, Object>> members = new ArrayList<>();
            for (String uid : memberUserIds) {
                if (uid != null && !uid.isBlank()) {
                    members.add(Map.of("Member_Account", uid.trim()));
                }
            }
            if (!members.isEmpty()) {
                body.put("MemberList", members);
            }
        }
        Map<String, Object> resp = postRequired(
            "group_open_http_svc/create_group", body, "createGroup owner=" + ownerUserId);
        String groupId = str(resp.get("GroupId"));
        if (groupId == null || groupId.isBlank()) {
            throw new ImRestException("IM_CREATE_GROUP_NO_ID", 0);
        }
        return groupId.trim();
    }

    /**
     * 服务端代成员退群。腾讯 IM REST 不允许用成员 identifier 调 quit_group（60010），
     * 管理员 quit_group 也会 10007；实际可用 delete_group_member。
     */
    public void quitGroup(String groupId, String memberUserId) {
        if (api == null) {
            throw new ImRestException("IM_NOT_CONFIGURED", 0);
        }
        if (groupId == null || groupId.isBlank() || memberUserId == null || memberUserId.isBlank()) {
            throw new ImRestException("INVALID_INPUT", 0);
        }
        deleteGroupMembers(groupId, List.of(memberUserId.trim()), false);
    }

    /** 删除群成员（delete_group_member），用于踢人，产生 KICKED GroupTips。 */
    public void deleteGroupMembers(String groupId, List<String> memberUserIds, boolean silence) {
        if (api == null) {
            throw new ImRestException("IM_NOT_CONFIGURED", 0);
        }
        if (groupId == null || groupId.isBlank() || memberUserIds == null || memberUserIds.isEmpty()) {
            throw new ImRestException("INVALID_INPUT", 0);
        }
        List<String> accounts = memberUserIds.stream()
            .filter(id -> id != null && !id.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
        if (accounts.isEmpty()) {
            throw new ImRestException("INVALID_INPUT", 0);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("GroupId", groupId.trim());
        body.put("MemberToDel_Account", accounts);
        if (silence) {
            body.put("Silence", 1);
        }
        postRequired("group_open_http_svc/delete_group_member", body,
            "deleteGroupMembers group=" + groupId);
        roleCache.evictAll(groupId, accounts);
    }

    /** 解散群（destroy_group）。 */
    public void destroyGroup(String groupId) {
        if (api == null) {
            throw new ImRestException("IM_NOT_CONFIGURED", 0);
        }
        if (groupId == null || groupId.isBlank()) {
            throw new ImRestException("INVALID_INPUT", 0);
        }
        postRequired("group_open_http_svc/destroy_group",
            Map.of("GroupId", groupId.trim()), "destroyGroup group=" + groupId);
        roleCache.evictGroup(groupId);
    }

    /** 转让群主（change_group_owner）。previousOwnerUserId 用于缓存失效（可空）。 */
    public void changeGroupOwner(String groupId, String newOwnerUserId) {
        changeGroupOwner(groupId, newOwnerUserId, null);
    }

    public void changeGroupOwner(String groupId, String newOwnerUserId, String previousOwnerUserId) {
        if (api == null) {
            throw new ImRestException("IM_NOT_CONFIGURED", 0);
        }
        if (groupId == null || groupId.isBlank() || newOwnerUserId == null || newOwnerUserId.isBlank()) {
            throw new ImRestException("INVALID_INPUT", 0);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("GroupId", groupId.trim());
        body.put("NewOwner_Account", newOwnerUserId.trim());
        postRequired("group_open_http_svc/change_group_owner", body,
            "changeGroupOwner group=" + groupId);
        roleCache.evict(groupId, newOwnerUserId.trim());
        if (previousOwnerUserId != null && !previousOwnerUserId.isBlank()) {
            roleCache.evict(groupId, previousOwnerUserId.trim());
        }
    }

    /** 修改成员角色（modify_group_member_info · Role）。 */
    public void modifyMemberImRole(String groupId, String userId, String imRole) {
        if (api == null) {
            throw new ImRestException("IM_NOT_CONFIGURED", 0);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("GroupId", groupId.trim());
        body.put("Member_Account", userId.trim());
        body.put("Role", imRole);
        postRequired("group_open_http_svc/modify_group_member_info", body,
            "modifyMemberImRole group=" + groupId + " user=" + userId + " role=" + imRole);
        roleCache.evict(groupId, userId.trim());
    }

    /** 成员禁言（modify_group_member_info · MuteTime，0 表示解除）。 */
    public void modifyMemberMute(String groupId, String userId, long mutedUntilSec) {
        if (api == null) {
            throw new ImRestException("IM_NOT_CONFIGURED", 0);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("GroupId", groupId.trim());
        body.put("Member_Account", userId.trim());
        body.put("MuteTime", mutedUntilSec);
        postRequired("group_open_http_svc/modify_group_member_info", body,
            "modifyMemberMute group=" + groupId + " user=" + userId);
    }

    /** 全员禁言（modify_group_base_info · ShutUpAllMember）。 */
    public void modifyGroupMuteAll(String groupId, boolean shutUpAll) {
        if (api == null) {
            throw new ImRestException("IM_NOT_CONFIGURED", 0);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("GroupId", groupId.trim());
        body.put("ShutUpAllMember", shutUpAll ? "On" : "Off");
        postRequired("group_open_http_svc/modify_group_base_info", body,
            "modifyGroupMuteAll group=" + groupId);
    }

    /**
     * 增加群成员（add_group_member）。适用于 Public 等可直接拉人入群的类型。
     * 单次最多 300 人，超出时分批调用。
     */
    @SuppressWarnings("unchecked")
    public List<AddGroupMemberResult> addGroupMembers(String groupId, List<String> memberUserIds, boolean silence) {
        if (api == null) {
            throw new ImRestException("IM_NOT_CONFIGURED", 0);
        }
        if (groupId == null || groupId.isBlank() || memberUserIds == null || memberUserIds.isEmpty()) {
            return List.of();
        }
        List<AddGroupMemberResult> all = new ArrayList<>();
        String gid = groupId.trim();
        for (int i = 0; i < memberUserIds.size(); i += 300) {
            List<String> batch = memberUserIds.subList(i, Math.min(i + 300, memberUserIds.size()));
            List<Map<String, Object>> memberList = new ArrayList<>();
            for (String uid : batch) {
                memberList.add(Map.of("Member_Account", uid));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("GroupId", gid);
            body.put("MemberList", memberList);
            if (silence) {
                body.put("Silence", 1);
            }
            Map<String, Object> resp = postRequired(
                "group_open_http_svc/add_group_member", body, "addGroupMembers group=" + gid);
            Object list = resp.get("MemberList");
            if (!(list instanceof List<?> arr)) {
                continue;
            }
            for (Object item : arr) {
                if (!(item instanceof Map<?, ?> m)) {
                    continue;
                }
                String account = str(m.get("Member_Account"));
                all.add(new AddGroupMemberResult(
                    account,
                    parseInt(m.get("Result")),
                    str(m.get("ResultInfo"))));
            }
            roleCache.evictAll(gid, batch);
        }
        return all;
    }

    /** 群成员 userId 列表（get_group_member_info，分页）。 */
    public List<String> listGroupMemberUserIds(String groupId, int maxMembers) {
        if (api == null || groupId == null || groupId.isBlank()) {
            return List.of();
        }
        int limit = maxMembers <= 0 ? Integer.MAX_VALUE : maxMembers;
        List<String> out = new ArrayList<>();
        int offset = 0;
        int pageSize = Math.min(100, limit == Integer.MAX_VALUE ? 100 : limit);
        while (out.size() < limit) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("GroupId", groupId);
            body.put("Limit", pageSize);
            body.put("Offset", offset);
            Map<?, ?> raw = postReturning(
                buildUrl("group_open_http_svc/get_group_member_info"), body, "listGroupMemberUserIds");
            if (raw == null || !imOk(raw)) {
                break;
            }
            Object list = raw.get("MemberList");
            if (!(list instanceof List<?> arr) || arr.isEmpty()) {
                break;
            }
            for (Object item : arr) {
                if (!(item instanceof Map<?, ?> m)) {
                    continue;
                }
                String account = str(m.get("Member_Account"));
                if (account != null && !account.isBlank()) {
                    out.add(account.trim());
                    if (out.size() >= limit) {
                        return out;
                    }
                }
            }
            if (arr.size() < pageSize) {
                break;
            }
            offset += pageSize;
        }
        return out;
    }

    public record OnlineInstance(long instId, String platform, String customIdentifier, String status) {}

    public List<OnlineInstance> queryOnlineInstances(String userId) {
        if (api == null || userId == null || userId.isBlank()) {
            return List.of();
        }
        Map<String, Object> body = Map.of(
            "To_Account", List.of(userId),
            "IsNeedDetail", 1,
            "IsReturnInstid", 1);
        Map<?, ?> resp = postReturning(buildUrl("openim/query_online_status"), body,
            "queryOnlineStatus userId=" + userId);
        if (resp == null) {
            return List.of();
        }
        Object code = resp.get("ErrorCode");
        if (code instanceof Number n && n.intValue() != 0) {
            log.warn("IM queryOnlineStatus failed userId={} resp={}", userId, resp);
            return List.of();
        }
        Object queryResult = resp.get("QueryResult");
        if (!(queryResult instanceof List<?> results)) {
            return List.of();
        }
        List<OnlineInstance> out = new ArrayList<>();
        for (Object item : results) {
            if (!(item instanceof Map<?, ?> entry)) {
                continue;
            }
            Object detail = entry.get("Detail");
            if (!(detail instanceof List<?> details)) {
                continue;
            }
            for (Object row : details) {
                if (!(row instanceof Map<?, ?> dm)) {
                    continue;
                }
                Object inst = dm.get("Instid");
                if (!(inst instanceof Number instNum)) {
                    continue;
                }
                out.add(new OnlineInstance(
                    instNum.longValue(),
                    stringValue(dm.get("Platform")),
                    stringValue(dm.get("CustomIdentifier")),
                    stringValue(dm.get("Status"))));
            }
        }
        return out;
    }

    public void adminKickDevices(String userId, List<Long> instIds) {
        if (api == null || userId == null || userId.isBlank() || instIds == null || instIds.isEmpty()) {
            return;
        }
        List<Long> unique = instIds.stream().distinct().toList();
        Map<String, Object> body = Map.of(
            "To_Account", userId,
            "Insts", unique);
        post(buildUrl("im_open_status/admin_kick_device"), body, "adminKickDevice userId=" + userId);
    }

    /**
     * 失效账号登录状态（{@code im_open_login_svc/kick}）。
     * 该账号此前签发的 UserSig 全部失效，在线连接会被踢下线。
     * 重新签发的 UserSig 仍可登录。
     */
    public void kickAccount(String userId) {
        if (api == null || userId == null || userId.isBlank()) {
            return;
        }
        post(buildUrl("im_open_login_svc/kick"), Map.of("UserID", userId), "kickAccount userId=" + userId);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> postRequired(String path, Map<String, Object> body, String tag) {
        if (api == null) {
            throw new ImRestException("IM_NOT_CONFIGURED", 0);
        }
        Map<?, ?> raw = postReturning(buildUrl(path), body, tag);
        return parseRequiredResponse(raw, tag);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseRequiredResponse(Map<?, ?> raw, String tag) {
        if (raw == null) {
            throw new ImRestException("IM_REST_UNAVAILABLE", 0);
        }
        Map<String, Object> resp = (Map<String, Object>) raw;
        Object code = resp.get("ErrorCode");
        int err = code instanceof Number n ? n.intValue() : 0;
        if (err != 0) {
            String info = resp.get("ErrorInfo") == null ? "IM_ERROR" : resp.get("ErrorInfo").toString();
            log.warn("IM REST {} failed code={} info={} resp={}", tag, err, info, resp);
            throw new ImRestException(info, err);
        }
        return resp;
    }

    private String buildUrl(String path) {
        return buildUrl(path, props.restAdminAccount(), api.genUserSig(props.restAdminAccount(), 86400));
    }

    private String buildUrl(String path, String identifier, String userSig) {
        long random = (long) rng.nextInt(Integer.MAX_VALUE);
        return props.restBaseUrl() + path
            + "?sdkappid=" + sdkAppId
            + "&identifier=" + identifier
            + "&usersig=" + userSig
            + "&random=" + random
            + "&contenttype=json";
    }

    private void post(String url, Map<String, Object> body, String tag) {
        try {
            String payload = json.writeValueAsString(body);
            Request req = new Request.Builder().url(url)
                .post(RequestBody.create(payload, JSON))
                .build();
            try (Response resp = http.newCall(req).execute()) {
                String txt = resp.body() == null ? "" : resp.body().string();
                if (!resp.isSuccessful()) {
                    log.warn("IM REST {} http={} body={}", tag, resp.code(), txt);
                    return;
                }
                Map<?, ?> r = json.readValue(txt, Map.class);
                Object code = r.get("ErrorCode");
                if (code instanceof Number n && n.intValue() != 0) {
                    log.warn("IM REST {} resp={}", tag, txt);
                }
            }
        } catch (IOException e) {
            log.warn("IM REST {} io={}", tag, e.getMessage());
        }
    }

    private Map<?, ?> postReturning(String url, Map<String, Object> body, String tag) {
        try {
            String payload = json.writeValueAsString(body);
            Request req = new Request.Builder().url(url)
                .post(RequestBody.create(payload, JSON))
                .build();
            try (Response resp = http.newCall(req).execute()) {
                String txt = resp.body() == null ? "" : resp.body().string();
                if (!resp.isSuccessful()) {
                    log.warn("IM REST {} http={} body={}", tag, resp.code(), txt);
                    return null;
                }
                return json.readValue(txt, Map.class);
            }
        } catch (IOException e) {
            log.warn("IM REST {} io={}", tag, e.getMessage());
            return null;
        }
    }
}
