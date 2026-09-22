package com.chat99.server.adminapi;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class AdminRelationsService {

    private static final int KEYWORD_SCAN_LIMIT = 500;

    private final AdminUserDetailService userDetail;

    public AdminRelationsService(AdminUserDetailService userDetail) {
        this.userDetail = userDetail;
    }

    public FriendsListResponse listFriends(String userUid, String keyword, int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safeSize = clampPageSize(pageSize);
        if (keywordBlank(keyword)) {
            AdminUserDetailService.PagedFriendsResponse raw =
                userDetail.listFriends(userUid, safePage, safeSize);
            return new FriendsListResponse(
                raw.items().stream().map(f -> toFriendRelation(userUid, f)).toList(),
                raw.total(),
                raw.page(),
                raw.pageSize(),
                raw.truncated(),
                null);
        }
        AdminUserDetailService.PagedFriendsResponse raw =
            userDetail.listFriends(userUid, 1, KEYWORD_SCAN_LIMIT);
        List<FriendRelationItem> filtered = raw.items().stream()
            .map(f -> toFriendRelation(userUid, f))
            .filter(f -> matchesKeyword(keyword, f.friendUid(), f.friendNickname(), f.nickname()))
            .toList();
        return paginateFriends(filtered, safePage, safeSize, raw.truncated());
    }

    public GroupsListResponse listGroups(String userUid, String keyword, int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safeSize = clampPageSize(pageSize);
        if (keywordBlank(keyword)) {
            AdminUserDetailService.PagedGroupsResponse raw =
                userDetail.listGroups(userUid, safePage, safeSize);
            return new GroupsListResponse(
                raw.items().stream().map(this::toGroupRelation).toList(),
                raw.total(),
                raw.page(),
                raw.pageSize(),
                raw.truncated(),
                null);
        }
        AdminUserDetailService.PagedGroupsResponse raw =
            userDetail.listGroups(userUid, 1, KEYWORD_SCAN_LIMIT);
        List<GroupRelationItem> filtered = raw.items().stream()
            .map(this::toGroupRelation)
            .filter(g -> matchesKeyword(keyword, g.groupId(), g.groupName(), g.groupType(), g.role()))
            .toList();
        return paginateGroups(filtered, safePage, safeSize, raw.truncated());
    }

    public RelatedAccountsListResponse listSameIp(
        String userUid, String ipFilter, int page, int pageSize) {
        AdminUserDetailService.PagedRelatedWithHintResponse raw =
            userDetail.listRelatedByIp(userUid, ipFilter, page, pageSize);
        return new RelatedAccountsListResponse(
            raw.items().stream().map(this::toRelatedAccount).toList(),
            raw.total(),
            raw.page(),
            raw.pageSize(),
            raw.truncated(),
            raw.hint());
    }

    public RelatedAccountsListResponse listSameDevice(
        String userUid, String deviceId, int page, int pageSize) {
        AdminUserDetailService.PagedRelatedWithHintResponse raw =
            userDetail.listRelatedByDeviceFlexible(userUid, deviceId, page, pageSize);
        return new RelatedAccountsListResponse(
            raw.items().stream().map(this::toRelatedAccount).toList(),
            raw.total(),
            raw.page(),
            raw.pageSize(),
            raw.truncated(),
            raw.hint());
    }

    private FriendRelationItem toFriendRelation(
        String userUid, AdminUserDetailService.FriendItem f) {
        String nick = f.nickname() != null ? f.nickname() : "";
        return new FriendRelationItem(
            userUid,
            f.friendUid(),
            nick,
            nick,
            f.addTime(),
            f.friendAvatarFileName());
    }

    private GroupRelationItem toGroupRelation(AdminUserDetailService.GroupItem g) {
        String role = g.groupType() != null ? g.groupType() : "";
        return new GroupRelationItem(
            g.groupId(),
            g.groupName(),
            g.groupType(),
            g.memberCount(),
            g.joinTime(),
            g.faceUrl(),
            role);
    }

    private RelatedAccountItem toRelatedAccount(AdminUserDetailService.RelatedUserItem u) {
        return new RelatedAccountItem(
            u.userUid(),
            u.nickname(),
            u.phoneNum(),
            u.userStatus(),
            u.sharedIp(),
            u.sharedDeviceId(),
            u.userAvatarFileName());
    }

    private static FriendsListResponse paginateFriends(
        List<FriendRelationItem> all, int page, int pageSize, boolean scanTruncated) {
        int from = (page - 1) * pageSize;
        int to = Math.min(from + pageSize, all.size());
        List<FriendRelationItem> slice =
            from >= all.size() ? List.of() : all.subList(from, to);
        boolean truncated = scanTruncated || to < all.size();
        return new FriendsListResponse(slice, all.size(), page, pageSize, truncated, null);
    }

    private static GroupsListResponse paginateGroups(
        List<GroupRelationItem> all, int page, int pageSize, boolean scanTruncated) {
        int from = (page - 1) * pageSize;
        int to = Math.min(from + pageSize, all.size());
        List<GroupRelationItem> slice =
            from >= all.size() ? List.of() : all.subList(from, to);
        boolean truncated = scanTruncated || to < all.size();
        return new GroupsListResponse(slice, all.size(), page, pageSize, truncated, null);
    }

    private static boolean keywordBlank(String keyword) {
        return keyword == null || keyword.isBlank();
    }

    private static boolean matchesKeyword(String keyword, String... fields) {
        if (keywordBlank(keyword)) {
            return true;
        }
        String kw = keyword.trim().toLowerCase(Locale.ROOT);
        for (String field : fields) {
            if (field != null && field.toLowerCase(Locale.ROOT).contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private static int clampPageSize(int pageSize) {
        return Math.min(Math.max(pageSize, 1), 100);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FriendRelationItem(
        String userUid,
        String friendUid,
        String friendNickname,
        String nickname,
        Long addTime,
        String friendAvatarFileName) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GroupRelationItem(
        String groupId,
        String groupName,
        String groupType,
        Integer memberCount,
        Long joinTime,
        String faceUrl,
        String role) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RelatedAccountItem(
        String userUid,
        String nickname,
        String phoneNum,
        Integer userStatus,
        String sharedIp,
        String sharedDeviceId,
        String userAvatarFileName) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FriendsListResponse(
        List<FriendRelationItem> items,
        int total,
        int page,
        int pageSize,
        boolean truncated,
        String hint) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GroupsListResponse(
        List<GroupRelationItem> items,
        int total,
        int page,
        int pageSize,
        boolean truncated,
        String hint) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RelatedAccountsListResponse(
        List<RelatedAccountItem> items,
        int total,
        int page,
        int pageSize,
        boolean truncated,
        String hint) {}
}
