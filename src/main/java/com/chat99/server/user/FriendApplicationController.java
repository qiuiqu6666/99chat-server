package com.chat99.server.user;

import com.chat99.server.user.FriendApplicationService.AcceptRequest;
import com.chat99.server.user.FriendApplicationService.CursorPage;
import com.chat99.server.user.FriendApplicationService.HistoryItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/friend-application")
public class FriendApplicationController {

    private final FriendApplicationService service;

    public FriendApplicationController(FriendApplicationService service) {
        this.service = service;
    }

    public record PageResponse(
        java.util.List<HistoryItem> content,
        Instant nextCursor,
        boolean hasMore) {}

    @GetMapping("/history")
    public ResponseEntity<PageResponse> history(
        Authentication auth,
        @RequestParam(required = false) Instant cursor,
        @RequestParam(defaultValue = "100")
        @Min(1) @Max(200) int limit) {
        String userId = (String) auth.getPrincipal();
        CursorPage page = service.history(userId, cursor, limit);
        return ResponseEntity.ok(new PageResponse(page.content(), page.nextCursor(), page.hasMore()));
    }

    public record AcceptPayload(
        @NotBlank String peerUserId,
        @NotNull Instant addTime,
        String addWording,
        @NotBlank String addSource) {}

    @PostMapping("/accept")
    public ResponseEntity<Void> accept(Authentication auth,
                                       @Valid @RequestBody AcceptPayload payload) {
        String userId = (String) auth.getPrincipal();
        service.acceptApplication(userId, new AcceptRequest(
            payload.peerUserId(),
            payload.addTime(),
            payload.addWording(),
            payload.addSource()));
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/history/{id}")
    public Map<String, Object> deleteHistory(Authentication auth, @PathVariable long id) {
        String userId = (String) auth.getPrincipal();
        return service.deleteHistory(userId, id);
    }
}
