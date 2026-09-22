/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.adminapi;

import com.chat99.server.adminapi.AdminAccess;
import com.chat99.server.adminapi.AdminChatComplaintService;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value={"/api/v1/chat-complaints"})
@JsonNaming(value=PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AdminChatComplaintController {
    private final AdminChatComplaintService complaintService;

    public AdminChatComplaintController(AdminChatComplaintService complaintService) {
        this.complaintService = complaintService;
    }

    @GetMapping
    public AdminChatComplaintService.ComplaintListResponse list(Authentication auth, @RequestParam(name="chat_type", required=false) String chatType, @RequestParam(name="reporter_uid", required=false) String reporterUid, @RequestParam(name="reported_uid", required=false) String reportedUid, @RequestParam(name="group_id", required=false) String groupId, @RequestParam(required=false) String status, @RequestParam(defaultValue="1") int page, @RequestParam(name="page_size", defaultValue="10") int pageSize) {
        AdminAccess.requirePermission((Authentication)auth, (String)"user.read");
        return this.complaintService.list(chatType, reporterUid, reportedUid, groupId, status, page, pageSize);
    }

    @PostMapping(value={"/{id}/status"})
    public AdminChatComplaintService.ComplaintItem updateStatus(HttpServletRequest http, Authentication auth, @PathVariable long id, @RequestBody AdminChatComplaintService.UpdateStatusRequest body) {
        return this.complaintService.updateStatus(http, auth, id, body);
    }
}
