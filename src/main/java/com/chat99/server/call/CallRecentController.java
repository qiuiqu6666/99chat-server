package com.chat99.server.call;

import com.chat99.server.common.ApiResponse;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class CallRecentController {

    private final CallRecentService recentService;

    public CallRecentController(CallRecentService recentService) {
        this.recentService = recentService;
    }

    @GetMapping("/calls/recent")
    public ResponseEntity<CallRecentService.RecentPageView> recent(Authentication auth,
                                                                   @RequestParam(defaultValue = "all") String filter,
                                                                   @RequestParam(defaultValue = "0") int page,
                                                                   @RequestParam(defaultValue = "20") int pageSize) {
        String userId = (String) auth.getPrincipal();
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(recentService.listRecent(userId, filter, page, pageSize));
    }

    @GetMapping("/calls/{callId}")
    public ResponseEntity<ApiResponse<CallRecentService.RecentItemView>> getOne(Authentication auth,
                                                                                @PathVariable String callId) {
        String userId = (String) auth.getPrincipal();
        return recentService.findRecentItem(userId, callId)
            .map(view -> ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(view)))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "CALL_RECORD_NOT_FOUND"));
    }

    @GetMapping("/calls/livekit/status/{callId}")
    public ResponseEntity<ApiResponse<CallRecentService.CallStatusView>> status(
        Authentication auth, @PathVariable String callId) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(recentService.getStatus((String) auth.getPrincipal(), callId)));
    }

    @DeleteMapping("/calls/recent/{callId}")
    public Map<String, Boolean> deleteOne(Authentication auth, @PathVariable String callId) {
        recentService.deleteOne((String) auth.getPrincipal(), callId);
        return Map.of("ok", true);
    }

    @DeleteMapping("/calls/recent")
    public CallRecentService.DeleteAllResult deleteAll(Authentication auth,
                                                       @RequestParam(defaultValue = "all") String filter) {
        return recentService.deleteAll((String) auth.getPrincipal(), filter);
    }
}
