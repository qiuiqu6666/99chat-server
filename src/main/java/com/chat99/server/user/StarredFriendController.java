package com.chat99.server.user;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StarredFriendController {

    private final StarredFriendService starredFriendService;

    public StarredFriendController(StarredFriendService starredFriendService) {
        this.starredFriendService = starredFriendService;
    }

    /** 星标好友列表（通讯录页置顶/标识用）。 */
    @GetMapping("/me/starred-friends")
    public StarredFriendService.StarredFriendListResponse list(Authentication auth) {
        return starredFriendService.list((String) auth.getPrincipal());
    }

    /** 设置星标（幂等）。 */
    @PutMapping("/me/starred-friends/{friendUserId}")
    public StarredFriendService.StarredFriendMutationResponse star(Authentication auth,
                                                                   @PathVariable String friendUserId) {
        return starredFriendService.star((String) auth.getPrincipal(), friendUserId);
    }

    /** 取消星标（幂等）。 */
    @DeleteMapping("/me/starred-friends/{friendUserId}")
    public StarredFriendService.StarredFriendMutationResponse unstar(Authentication auth,
                                                                     @PathVariable String friendUserId) {
        return starredFriendService.unstar((String) auth.getPrincipal(), friendUserId);
    }
}
