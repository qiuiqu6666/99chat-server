package com.chat99.server.adminapi;

import com.chat99.server.feedback.FeedbackDiagnosticsRepository;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/feedback")
public class AdminFeedbackDiagnosticsController {
    private final FeedbackDiagnosticsRepository repository;
    public AdminFeedbackDiagnosticsController(FeedbackDiagnosticsRepository repository) { this.repository = repository; }

    @GetMapping("/{id}/diagnostics")
    public ResponseEntity<byte[]> download(Authentication auth, @PathVariable long id) {
        AdminAccess.requirePermission(auth, "user.read");
        var attachment = repository.findById(id).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "DIAGNOSTICS_NOT_FOUND"));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=feedback-" + id + "-diagnostics.txt")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header("X-Content-Type-Options", "nosniff")
            .body(attachment.getReport());
    }
}
