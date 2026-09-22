package com.chat99.server.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/friend-requests")
public class FriendRequestController {

    private final FriendRequestService requestService;

    public FriendRequestController(FriendRequestService requestService) {
        this.requestService = requestService;
    }

    public record CreatePayload(
        @NotBlank String targetUserId,
        String addWording,
        @NotBlank String addSource) {}

    @PostMapping
    public FriendRequestService.CreateRequestResult create(Authentication auth,
                                                           @Valid @RequestBody CreatePayload body) {
        return requestService.createRequest(
            (String) auth.getPrincipal(),
            body.targetUserId(),
            body.addWording(),
            body.addSource());
    }

    @GetMapping("/incoming")
    public FriendRequestService.RequestListResponse incoming(Authentication auth,
                                                             @RequestParam(defaultValue = "100") int limit) {
        return requestService.listIncoming((String) auth.getPrincipal(), limit);
    }

    public record BatchDeletePayload(@NotEmpty List<Long> ids) {}

    @DeleteMapping("/incoming/batch")
    public FriendRequestService.BatchDeleteResponse deleteIncomingBatch(
        Authentication auth,
        @Valid @RequestBody BatchDeletePayload body) {
        return requestService.deleteIncomingBatch((String) auth.getPrincipal(), body.ids());
    }

    @DeleteMapping("/incoming/{id}")
    public FriendRequestService.DeleteResponse deleteIncoming(Authentication auth, @PathVariable long id) {
        return requestService.deleteIncoming((String) auth.getPrincipal(), id);
    }

    @GetMapping("/outgoing")
    public FriendRequestService.RequestListResponse outgoing(Authentication auth,
                                                             @RequestParam(defaultValue = "100") int limit) {
        return requestService.listOutgoing((String) auth.getPrincipal(), limit);
    }

    @GetMapping("/sent")
    public FriendRequestService.RequestListResponse sent(Authentication auth,
                                                         @RequestParam(defaultValue = "100") int limit) {
        return requestService.listSent((String) auth.getPrincipal(), limit);
    }

    @DeleteMapping("/sent/batch")
    public FriendRequestService.BatchDeleteResponse deleteSentBatch(
        Authentication auth,
        @Valid @RequestBody BatchDeletePayload body) {
        return requestService.deleteSentBatch((String) auth.getPrincipal(), body.ids());
    }

    @DeleteMapping("/sent/{id}")
    public FriendRequestService.DeleteResponse deleteSent(Authentication auth, @PathVariable long id) {
        return requestService.deleteSent((String) auth.getPrincipal(), id);
    }

    @PostMapping("/{id}/accept")
    public Map<String, Object> accept(Authentication auth, @PathVariable long id) {
        requestService.acceptRequest((String) auth.getPrincipal(), id);
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/reject")
    public Map<String, Object> reject(Authentication auth, @PathVariable long id) {
        requestService.rejectRequest((String) auth.getPrincipal(), id);
        return Map.of("ok", true);
    }
}
