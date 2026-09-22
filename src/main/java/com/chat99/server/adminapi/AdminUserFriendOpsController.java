package com.chat99.server.adminapi;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/{userUid}/friends")
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AdminUserFriendOpsController {

    private final AdminUserFriendOpsService friendOps;

    public AdminUserFriendOpsController(AdminUserFriendOpsService friendOps) {
        this.friendOps = friendOps;
    }

    @PostMapping("/{peerUid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forceAdd(Authentication auth,
                         HttpServletRequest http,
                         @PathVariable @NotBlank String userUid,
                         @PathVariable @NotBlank String peerUid) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        friendOps.forceAdd(http, admin.username(), userUid, peerUid);
    }

    @DeleteMapping("/{peerUid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forceDelete(Authentication auth,
                            HttpServletRequest http,
                            @PathVariable @NotBlank String userUid,
                            @PathVariable @NotBlank String peerUid) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        friendOps.forceDelete(http, admin.username(), userUid, peerUid);
    }
}
