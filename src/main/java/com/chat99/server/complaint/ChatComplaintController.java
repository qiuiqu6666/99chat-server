/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.complaint;

import com.chat99.server.complaint.ChatComplaintController;
import com.chat99.server.complaint.ChatComplaintService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class ChatComplaintController {
    private final ChatComplaintService complaintService;

    public ChatComplaintController(ChatComplaintService complaintService) {
        this.complaintService = complaintService;
    }

    @PostMapping(value={"/me/complaints/c2c"}, consumes={"application/json"})
    public ChatComplaintService.SubmitResult submitC2cJson(Authentication auth, @Valid @RequestBody C2cComplaintBody body) throws IOException {
        return this.complaintService.submitC2c(ChatComplaintController.userId(auth), ChatComplaintController.toC2cRequest(body, List.of()));
    }

    @PostMapping(value={"/me/complaints/c2c"}, consumes={"multipart/form-data"})
    public ChatComplaintService.SubmitResult submitC2cMultipart(Authentication auth, @RequestParam(value="reportedUserId") String reportedUserId, @RequestParam(value="reason") String reason, @RequestParam(value="content", required=false) String content, @RequestParam(value="msgKey", required=false) String msgKey, @RequestParam(value="msgSeq", required=false) Long msgSeq, @RequestParam(value="clientVersion", required=false) String clientVersion, @RequestPart(value="screenshots", required=false) List<MultipartFile> screenshots) throws IOException {
        return this.complaintService.submitC2c(ChatComplaintController.userId(auth), new ChatComplaintService.SubmitRequest(reportedUserId, reason, content, msgKey, msgSeq, clientVersion, screenshots));
    }

    @PostMapping(value={"/me/complaints/group"}, consumes={"application/json"})
    public ChatComplaintService.SubmitResult submitGroupJson(Authentication auth, @Valid @RequestBody GroupComplaintBody body) throws IOException {
        return this.complaintService.submitGroup(ChatComplaintController.userId(auth), ChatComplaintController.toGroupRequest(body, List.of()));
    }

    @PostMapping(value={"/me/complaints/group"}, consumes={"multipart/form-data"})
    public ChatComplaintService.SubmitResult submitGroupMultipart(Authentication auth, @RequestParam(value="groupId") String groupId, @RequestParam(value="reportedUserId") String reportedUserId, @RequestParam(value="reason") String reason, @RequestParam(value="content", required=false) String content, @RequestParam(value="msgKey", required=false) String msgKey, @RequestParam(value="msgSeq", required=false) Long msgSeq, @RequestParam(value="clientVersion", required=false) String clientVersion, @RequestPart(value="screenshots", required=false) List<MultipartFile> screenshots) throws IOException {
        return this.complaintService.submitGroup(ChatComplaintController.userId(auth), new ChatComplaintService.GroupSubmitRequest(groupId, reportedUserId, reason, content, msgKey, msgSeq, clientVersion, screenshots));
    }

    private static String userId(Authentication auth) {
        return (String)auth.getPrincipal();
    }

    private static ChatComplaintService.SubmitRequest toC2cRequest(C2cComplaintBody body, List<MultipartFile> screenshots) {
        return new ChatComplaintService.SubmitRequest(body.reportedUserId(), body.reason(), body.content(), body.msgKey(), body.msgSeq(), body.clientVersion(), screenshots);
    }

    private static ChatComplaintService.GroupSubmitRequest toGroupRequest(GroupComplaintBody body, List<MultipartFile> screenshots) {
        return new ChatComplaintService.GroupSubmitRequest(body.groupId(), body.reportedUserId(), body.reason(), body.content(), body.msgKey(), body.msgSeq(), body.clientVersion(), screenshots);
    }





    public record C2cComplaintBody(@NotBlank String reportedUserId, @NotBlank String reason, String content, String msgKey, Long msgSeq, String clientVersion) {}

    public record GroupComplaintBody(@NotBlank String groupId, @NotBlank String reportedUserId, @NotBlank String reason, String content, String msgKey, Long msgSeq, String clientVersion) {}
}
