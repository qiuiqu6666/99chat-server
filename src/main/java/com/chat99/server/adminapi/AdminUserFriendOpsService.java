package com.chat99.server.adminapi;

import com.chat99.server.user.UserFriendService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AdminUserFriendOpsService {

    private final AdminUserManagementService users;
    private final UserFriendService friendService;
    private final AdminAuditService auditService;

    public AdminUserFriendOpsService(AdminUserManagementService users,
                                     UserFriendService friendService,
                                     AdminAuditService auditService) {
        this.users = users;
        this.friendService = friendService;
        this.auditService = auditService;
    }

    public void forceAdd(HttpServletRequest http, String adminUsername, String userUid, String peerUid) {
        users.requireUser(userUid);
        users.requireUser(peerUid);
        friendService.bindMutualFriends(userUid, peerUid);
        auditService.log(http, adminUsername, "user.friend.force_add", userUid,
            Map.of("peer_uid", peerUid));
    }

    public void forceDelete(HttpServletRequest http, String adminUsername, String userUid, String peerUid) {
        users.requireUser(userUid);
        users.requireUser(peerUid);
        friendService.forceDeleteMutual(userUid, peerUid);
        auditService.log(http, adminUsername, "user.friend.force_delete", userUid,
            Map.of("peer_uid", peerUid));
    }
}
