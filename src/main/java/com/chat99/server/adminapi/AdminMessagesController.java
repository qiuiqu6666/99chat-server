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
@RequestMapping("/api/v1/messages")
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AdminMessagesController {

    private final AdminMessagesService messages;

    public AdminMessagesController(AdminMessagesService messages) {
        this.messages = messages;
    }

    @GetMapping("/c2c")
    public AdminMessagesService.C2cMessagesResponse c2c(
        Authentication auth,
        @RequestParam(name = "user_a") @NotBlank String userA,
        @RequestParam(name = "user_b") @NotBlank String userB,
        @RequestParam(required = false) String keyword,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(name = "page_size", defaultValue = "30") int pageSize,
        @RequestParam(name = "last_msg_key", required = false) String lastMsgKey) {
        AdminAccess.requirePermission(auth, "user.read");
        return messages.listC2c(userA, userB, keyword, page, pageSize, lastMsgKey);
    }

    @GetMapping("/group")
    public AdminMessagesService.GroupMessagesResponse group(
        Authentication auth,
        @RequestParam(name = "g_id") @NotBlank String groupId,
        @RequestParam(required = false) String keyword,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(name = "page_size", defaultValue = "30") int pageSize,
        @RequestParam(name = "req_msg_seq", required = false) Long reqMsgSeq) {
        AdminAccess.requirePermission(auth, "group.read");
        return messages.listGroup(groupId, keyword, page, pageSize, reqMsgSeq);
    }
}
