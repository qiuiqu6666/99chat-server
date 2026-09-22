package com.chat99.server.adminapi;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/relations")
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AdminRelationsController {

    private final AdminRelationsService relations;

    public AdminRelationsController(AdminRelationsService relations) {
        this.relations = relations;
    }

    /** 好友列表（关系页） */
    @GetMapping("/friends")
    public AdminRelationsService.FriendsListResponse friends(
        Authentication auth,
        @RequestParam(name = "user_uid") @NotBlank String userUid,
        @RequestParam(required = false) String keyword,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        AdminAccess.requirePermission(auth, "user.read");
        return relations.listFriends(userUid, keyword, page, pageSize);
    }

    /** 加入群组（关系页） */
    @GetMapping("/groups")
    public AdminRelationsService.GroupsListResponse groups(
        Authentication auth,
        @RequestParam(name = "user_uid") @NotBlank String userUid,
        @RequestParam(required = false) String keyword,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        AdminAccess.requirePermission(auth, "user.read");
        return relations.listGroups(userUid, keyword, page, pageSize);
    }

    /** 同 IP 关联账号 */
    @GetMapping("/same-ip")
    public AdminRelationsService.RelatedAccountsListResponse sameIp(
        Authentication auth,
        @RequestParam(name = "user_uid") @NotBlank String userUid,
        @RequestParam(required = false) String ip,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        AdminAccess.requirePermission(auth, "user.read");
        return relations.listSameIp(userUid, ip, page, pageSize);
    }

    /** 同设备关联账号（user_uid 与 device_id 至少填一项） */
    @GetMapping("/same-device")
    public AdminRelationsService.RelatedAccountsListResponse sameDevice(
        Authentication auth,
        @RequestParam(name = "user_uid", required = false) String userUid,
        @RequestParam(name = "device_id", required = false) String deviceId,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        AdminAccess.requirePermission(auth, "user.read");
        return relations.listSameDevice(userUid, deviceId, page, pageSize);
    }
}
