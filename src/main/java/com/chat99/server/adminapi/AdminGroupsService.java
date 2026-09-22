package com.chat99.server.adminapi;

import com.chat99.server.group.GroupGameIdService;
import com.chat99.server.group.GroupGameService;
import com.chat99.server.group.GroupProfile;
import com.chat99.server.group.GroupProfileRepository;
import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImRestException;
import com.chat99.server.im.ImUserIdService;
import com.chat99.server.user.User;
import com.chat99.server.user.UserRepository;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AdminGroupsService {

    private static final int MAX_GROUP_SCAN = 10000;
    private static final int OPLOG_MSG_SCAN = 80;
    private static final int OPLOG_ALL_GROUP_SCAN = 40;

    private final ImAdminClient imAdmin;
    private final UserRepository userRepository;
    private final GroupProfileRepository groupProfileRepository;
    private final GroupGameService groupGameService;
    private final GroupGameIdService groupGameIdService;
    private final AdminAuditService auditService;
    private final ImUserIdService imUserIdService;

    public AdminGroupsService(ImAdminClient imAdmin,
                              UserRepository userRepository,
                              GroupProfileRepository groupProfileRepository,
                              GroupGameService groupGameService,
                              GroupGameIdService groupGameIdService,
                              AdminAuditService auditService,
                              ImUserIdService imUserIdService) {
        this.imAdmin = imAdmin;
        this.userRepository = userRepository;
        this.groupProfileRepository = groupProfileRepository;
        this.groupGameService = groupGameService;
        this.groupGameIdService = groupGameIdService;
        this.auditService = auditService;
        this.imUserIdService = imUserIdService;
    }

    public GroupListResponse listGroups(
        String keyword, String gStatus, int page, int pageSize, String sort) {
        int safePage = Math.max(page, 1);
        int safeSize = clampPageSize(pageSize);
        String normalizedSort = normalizeSort(sort);
        Specification<GroupProfile> spec = buildProfileSpec(keyword, gStatus);
        Page<GroupProfile> profilePage = groupProfileRepository.findAll(
            spec,
            PageRequest.of(safePage - 1, safeSize, toProfileSort(normalizedSort)));
        List<String> pageIds = profilePage.getContent().stream()
            .map(GroupProfile::getGroupId)
            .toList();
        Map<String, ImAdminClient.GroupAdminInfo> infoMap =
            pageIds.isEmpty() ? Map.of() : imAdmin.fetchGroupAdminInfoMap(pageIds);

        List<GroupListItem> rows = new ArrayList<>();
        for (GroupProfile profile : profilePage.getContent()) {
            GroupListItem item = toListItem(profile, infoMap.get(profile.getGroupId()));
            if (!matchesGStatus(item, gStatus)) {
                continue;
            }
            rows.add(item);
        }
        long catalogTotal = groupProfileRepository.count(spec);
        return new GroupListResponse(
            rows,
            (int) Math.min(catalogTotal, Integer.MAX_VALUE),
            safePage,
            safeSize,
            normalizedSort,
            (int) Math.min(catalogTotal, Integer.MAX_VALUE),
            false);
    }

    public GroupDetailResponse getDetail(String groupId) {
        validateGroupId(groupId);
        ImAdminClient.GroupAdminInfo info = imAdmin.fetchGroupAdminInfo(groupId.trim())
            .orElseThrow(() -> new AdminApiException(HttpStatus.NOT_FOUND, "group_not_found", "group_not_found"));
        GroupListItem item = toListItem(groupId.trim(), info);
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("g_id", item.gId());
        group.put("g_status", item.gStatus());
        group.put("g_name", item.gName());
        group.put("g_owner_user_uid", item.gOwnerUserUid());
        group.put("g_notice", null);
        group.put("max_member_count", item.maxMemberCount());
        group.put("g_member_count", item.gMemberCount());
        group.put("create_user_uid", item.createUserUid());
        group.put("create_user_nickname", item.createUserNickname());
        group.put("create_time", item.createTime());
        group.put("g_mute_mode", item.gMuteMode());
        group.put("g_custom_avatar", item.gCustomAvatar());
        group.put("group_mode", 1);
        group.put("owner_nickname", resolveNickname(item.gOwnerUserUid()));
        group.put("face_url", item.gCustomAvatar());
        group.put("group_type", info.type());
        group.put("game_enabled", groupGameService.isGameEnabled(groupId.trim()));
        group.put("gameid", groupGameIdService.getGameid(groupId.trim()));
        return new GroupDetailResponse("tencent_im", groupId.trim(), group);
    }

    public GroupGameEnabledResult setGameEnabled(HttpServletRequest http, String adminUsername,
                                                 String groupId, boolean gameEnabled) {
        validateGroupId(groupId);
        String gid = groupId.trim();
        if (imAdmin.fetchGroupAdminInfo(gid).isEmpty()) {
            throw new AdminApiException(HttpStatus.NOT_FOUND, "group_not_found", "group_not_found");
        }
        boolean effective = groupGameService.setGameEnabled(gid, gameEnabled);
        auditService.log(http, adminUsername, "group.game_enabled.set", null,
            Map.of("g_id", gid, "game_enabled", effective));
        return new GroupGameEnabledResult(true, gid, effective);
    }

    public GroupGameidResult setGameid(HttpServletRequest http, String adminUsername,
                                       String groupId, String gameid) {
        validateGroupId(groupId);
        String gid = groupId.trim();
        if (imAdmin.fetchGroupAdminInfo(gid).isEmpty()) {
            throw new AdminApiException(HttpStatus.NOT_FOUND, "group_not_found", "group_not_found");
        }
        String effective;
        try {
            effective = groupGameIdService.setGameid(gid, gameid);
        } catch (ImRestException e) {
            throw new AdminApiException(HttpStatus.BAD_GATEWAY, "IM_REST_ERROR", "IM_REST_ERROR");
        }
        auditService.log(http, adminUsername, "group.gameid.set", null,
            Map.of("g_id", gid, "gameid", effective));
        return new GroupGameidResult(true, gid, effective);
    }

    public GroupMembersResponse listMembers(String groupId, int page, int pageSize, String sort) {
        validateGroupId(groupId);
        String gid = groupId.trim();
        if (imAdmin.fetchGroupAdminInfo(gid).isEmpty()) {
            throw new AdminApiException(HttpStatus.NOT_FOUND, "group_not_found", "group_not_found");
        }
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 200);
        int total = imAdmin.countGroupMembers(gid);
        int offset = (safePage - 1) * safeSize;
        List<ImAdminClient.GroupMemberRow> raw =
            imAdmin.listGroupMemberRows(gid, offset, safeSize);
        List<GroupMemberItem> items = raw.stream().map(r -> toMemberItem(gid, r)).toList();
        items = sortMembers(items, sort);
        return new GroupMembersResponse(
            gid, items, total, safePage, safeSize, normalizeMemberSort(sort));
    }

    public OperationLogsResponse listOperationLogs(
        String groupId, String kindsRaw, int page, int pageSize) {
        validateGroupId(groupId);
        String gid = groupId.trim();
        ImAdminClient.GroupAdminInfo info = imAdmin.fetchGroupAdminInfo(gid)
            .orElseThrow(() -> new AdminApiException(HttpStatus.NOT_FOUND, "group_not_found", "group_not_found"));
        Set<String> kinds = parseKinds(kindsRaw);
        List<OperationLogItem> all = collectGroupEvents(gid, info.name(), kinds);
        return paginateOperationLogs(
            all, gid, info.name(), 1, kinds, page, pageSize, "group_operation_timeline", null);
    }

    public OperationLogsResponse listOperationLogsAll(String kindsRaw, int page, int pageSize) {
        Set<String> kinds = parseKinds(kindsRaw);
        List<String> groupIds = imAdmin.scanAppGroupIds(OPLOG_ALL_GROUP_SCAN);
        Map<String, ImAdminClient.GroupAdminInfo> infoMap = imAdmin.fetchGroupAdminInfoMap(groupIds);
        List<OperationLogItem> all = new ArrayList<>();
        for (String gid : groupIds) {
            ImAdminClient.GroupAdminInfo info = infoMap.get(gid);
            String name = info != null ? info.name() : null;
            all.addAll(collectGroupEvents(gid, name, kinds));
        }
        all.sort(Comparator.comparingLong(OperationLogItem::occurredAtMs).reversed());
        return paginateOperationLogs(
            all, null, null, null, kinds, page, pageSize, "group_operation_timeline_all", "all_groups");
    }

    private List<OperationLogItem> collectGroupEvents(
        String gid, String gName, Set<String> kinds) {
        List<OperationLogItem> out = new ArrayList<>();
        if (kinds.contains("im_system") || kinds.contains("im_system_message")) {
            for (ImAdminClient.GroupSystemEvent ev : imAdmin.listGroupSystemEvents(gid, OPLOG_MSG_SCAN)) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("msg_type", ev.msgType());
                detail.put("from_account", ev.fromAccount());
                detail.put("msg_key", ev.msgKey());
                detail.put("text_preview", ev.textPreview());
                out.add(new OperationLogItem(
                    "im_system_message",
                    "tencent_im_group_msg",
                    ev.occurredAtSec() * 1000L,
                    ev.summary(),
                    detail,
                    gid,
                    gName,
                    1));
            }
        }
        out.sort(Comparator.comparingLong(OperationLogItem::occurredAtMs).reversed());
        return out;
    }

    private OperationLogsResponse paginateOperationLogs(
        List<OperationLogItem> all,
        String topGid,
        String topGName,
        Integer topGroupMode,
        Set<String> kinds,
        int page,
        int pageSize,
        String source,
        String scope) {
        int safePage = Math.max(page, 1);
        int safeSize = clampPageSize(pageSize);
        int from = (safePage - 1) * safeSize;
        int to = Math.min(from + safeSize, all.size());
        List<OperationLogItem> slice = from >= all.size() ? List.of() : all.subList(from, to);
        return new OperationLogsResponse(
            source,
            scope,
            topGid,
            topGName,
            topGroupMode,
            new ArrayList<>(kinds),
            slice,
            all.size(),
            safePage,
            safeSize);
    }

    private GroupListItem toListItem(String gid, ImAdminClient.GroupAdminInfo info) {
        String owner = toBusinessUid(info != null ? info.ownerAccount() : null);
        return new GroupListItem(
            gid,
            info != null ? info.name() : gid,
            mapGStatus(info),
            owner,
            owner,
            resolveNickname(owner),
            info != null ? info.memberNum() : null,
            info != null ? info.maxMemberNum() : null,
            info != null && info.createTimeSec() != null ? info.createTimeSec() * 1000L : null,
            info != null ? info.shutUpAllMember() : null,
            info != null ? info.faceUrl() : null);
    }

    private GroupListItem toListItem(GroupProfile profile, ImAdminClient.GroupAdminInfo info) {
        String owner = profile.getOwnerUserId();
        if ((owner == null || owner.isBlank()) && info != null) {
            owner = info.ownerAccount();
        }
        owner = toBusinessUid(owner);
        int status = profile.isDismissed() ? -1 : mapGStatus(info);
        Integer memberCount = profile.getMemberCount() > 0
            ? profile.getMemberCount()
            : (info != null ? info.memberNum() : null);
        Long createTime = profile.getCreatedAt() != null
            ? profile.getCreatedAt().toEpochMilli()
            : null;
        if (info != null && info.createTimeSec() != null && info.createTimeSec() > 0) {
            createTime = info.createTimeSec() * 1000L;
        }
        String name = profile.getGroupName();
        if ((name == null || name.isBlank()) && info != null && info.name() != null) {
            name = info.name();
        }
        String avatar = profile.getAvatarUrl();
        if ((avatar == null || avatar.isBlank()) && info != null) {
            avatar = info.faceUrl();
        }
        return new GroupListItem(
            profile.getGroupId(),
            name != null && !name.isBlank() ? name : profile.getGroupId(),
            status,
            owner,
            owner,
            resolveNickname(owner),
            memberCount,
            info != null ? info.maxMemberNum() : null,
            createTime,
            info != null ? info.shutUpAllMember() : null,
            avatar);
    }

    private static Specification<GroupProfile> buildProfileSpec(String keyword, String gStatus) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (keyword != null && !keyword.isBlank()) {
                String kw = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                    cb.like(cb.lower(root.get("groupId")), kw),
                    cb.like(cb.lower(root.get("groupName")), kw)));
            }
            if (gStatus == null || gStatus.isBlank()) {
                predicates.add(cb.isFalse(root.get("dismissed")));
            } else if ("-1".equals(gStatus.trim())) {
                predicates.add(cb.isTrue(root.get("dismissed")));
            } else {
                predicates.add(cb.isFalse(root.get("dismissed")));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static Sort toProfileSort(String sort) {
        return switch (sort) {
            case "create_time_asc" -> Sort.by(Sort.Direction.ASC, "createdAt");
            case "g_member_count_desc" -> Sort.by(Sort.Direction.DESC, "memberCount");
            case "g_member_count_asc" -> Sort.by(Sort.Direction.ASC, "memberCount");
            case "g_id_desc" -> Sort.by(Sort.Direction.DESC, "groupId");
            case "g_id_asc" -> Sort.by(Sort.Direction.ASC, "groupId");
            default -> Sort.by(Sort.Direction.DESC, "createdAt");
        };
    }

    private GroupMemberItem toMemberItem(String gid, ImAdminClient.GroupMemberRow row) {
        String memberBiz = toBusinessUid(row.userUid());
        return new GroupMemberItem(
            null,
            memberBiz,
            gid,
            row.joinTimeSec() != null ? row.joinTimeSec() * 1000L : null,
            null,
            null,
            row.nameCard(),
            ImAdminClient.mapMemberRoleLabel(row.imRole()),
            resolveNickname(memberBiz));
    }

    private String toBusinessUid(String uidOrIm) {
        if (uidOrIm == null || uidOrIm.isBlank()) {
            return uidOrIm;
        }
        return imUserIdService.toBusinessForDisplay(uidOrIm.trim());
    }

    private String resolveNickname(String uid) {
        if (uid == null || uid.isBlank()) {
            return null;
        }
        String businessUid = toBusinessUid(uid.trim());
        return userRepository.findByUserId(businessUid).map(User::getNickname).orElse(null);
    }

    private static int mapGStatus(ImAdminClient.GroupAdminInfo info) {
        if (info == null) {
            return 0;
        }
        if ("On".equalsIgnoreCase(info.shutUpAllMember())) {
            return 2;
        }
        return 0;
    }

    private static boolean matchesKeyword(GroupListItem item, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String kw = keyword.trim().toLowerCase(Locale.ROOT);
        return containsIgnoreCase(item.gId(), kw) || containsIgnoreCase(item.gName(), kw);
    }

    private static boolean matchesGStatus(GroupListItem item, String gStatus) {
        if (gStatus == null || gStatus.isBlank()) {
            return item.gStatus() != -1;
        }
        return String.valueOf(item.gStatus()).equals(gStatus.trim());
    }

    private static boolean containsIgnoreCase(String value, String kw) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(kw);
    }

    private static void sortGroups(List<GroupListItem> rows, String sort) {
        Comparator<GroupListItem> cmp = switch (normalizeSort(sort)) {
            case "create_time_asc" -> Comparator.comparing(
                GroupListItem::createTime, Comparator.nullsLast(Long::compareTo));
            case "g_member_count_desc" -> Comparator.comparing(
                GroupListItem::gMemberCount, Comparator.nullsLast(Integer::compareTo)).reversed();
            case "g_member_count_asc" -> Comparator.comparing(
                GroupListItem::gMemberCount, Comparator.nullsLast(Integer::compareTo));
            case "g_id_desc" -> Comparator.comparing(GroupListItem::gId).reversed();
            case "g_id_asc" -> Comparator.comparing(GroupListItem::gId);
            default -> Comparator.comparing(
                GroupListItem::createTime, Comparator.nullsLast(Long::compareTo)).reversed();
        };
        rows.sort(cmp);
    }

    private static List<GroupMemberItem> sortMembers(List<GroupMemberItem> items, String sort) {
        List<GroupMemberItem> copy = new ArrayList<>(items);
        Comparator<GroupMemberItem> cmp = switch (normalizeMemberSort(sort)) {
            case "join_time_asc" -> Comparator.comparing(
                GroupMemberItem::joinTime, Comparator.nullsLast(Long::compareTo));
            case "user_uid_asc" -> Comparator.comparing(GroupMemberItem::userUid);
            case "user_uid_desc" -> Comparator.comparing(GroupMemberItem::userUid).reversed();
            case "role_desc" -> Comparator.comparing(GroupMemberItem::role, Comparator.nullsLast(String::compareTo)).reversed();
            default -> Comparator.comparing(
                GroupMemberItem::joinTime, Comparator.nullsLast(Long::compareTo)).reversed();
        };
        copy.sort(cmp);
        return copy;
    }

    private static Set<String> parseKinds(String raw) {
        Set<String> kinds = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            kinds.add("im_system");
            kinds.add("join_request");
            kinds.add("member_mute");
            return kinds;
        }
        for (String part : raw.split(",")) {
            String k = part.trim().toLowerCase(Locale.ROOT);
            if (k.isEmpty()) {
                continue;
            }
            if ("mute".equals(k)) {
                kinds.add("member_mute");
            } else {
                kinds.add(k);
            }
        }
        if (kinds.isEmpty()) {
            kinds.add("im_system");
        }
        return kinds;
    }

    private static void validateGroupId(String groupId) {
        if (groupId == null || !groupId.trim().matches("^[A-Za-z0-9@#_-]{1,128}$")) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "invalid g_id");
        }
    }

    private static int clampPageSize(int pageSize) {
        return Math.min(Math.max(pageSize, 1), 100);
    }

    private static String normalizeSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return "create_time_desc";
        }
        return sort.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeMemberSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return "join_time_desc";
        }
        return sort.trim().toLowerCase(Locale.ROOT);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GroupListItem(
        String gId,
        String gName,
        int gStatus,
        String gOwnerUserUid,
        String createUserUid,
        String createUserNickname,
        Integer gMemberCount,
        Integer maxMemberCount,
        Long createTime,
        String gMuteMode,
        String gCustomAvatar) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GroupListResponse(
        List<GroupListItem> items,
        int total,
        int page,
        int pageSize,
        String sort,
        int imTotal,
        boolean truncated) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GroupGameEnabledResult(boolean ok, String gId, boolean gameEnabled) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GroupGameidResult(boolean ok, String gId, String gameid) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GroupDetailResponse(String source, String gId, Map<String, Object> group) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GroupMemberItem(
        Long userRecordId,
        String userUid,
        String gId,
        Long joinTime,
        String beInviteUserId,
        Long beOwnerTime,
        String nicknameIngroup,
        String role,
        String userNickname) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GroupMembersResponse(
        String gId,
        List<GroupMemberItem> items,
        int total,
        int page,
        int pageSize,
        String sort) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record OperationLogItem(
        String kind,
        String sourceTable,
        long occurredAtMs,
        String summary,
        Map<String, Object> detail,
        String gId,
        String gName,
        Integer groupMode) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record OperationLogsResponse(
        String source,
        String scope,
        String gId,
        String gName,
        Integer groupMode,
        List<String> kinds,
        List<OperationLogItem> items,
        int total,
        int page,
        int pageSize) {}
}
