package com.chat99.server.notify;

import com.chat99.server.admin.AdminGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/im")
public class PlatformWalletNoticeAdminController {

    private final AdminGuard guard;
    private final PlatformWalletNoticeService noticeService;

    public PlatformWalletNoticeAdminController(AdminGuard guard, PlatformWalletNoticeService noticeService) {
        this.guard = guard;
        this.noticeService = noticeService;
    }

    public record SendBody(
        @NotBlank String toUserId,
        @NotBlank String noticeType,
        @NotBlank String title,
        String serviceName,
        String statusLabel,
        String summary,
        List<PlatformWalletNoticeRow> rows,
        String actionLabel,
        String actionUrl,
        String orderId) {}

    @PostMapping("/platform-wallet-notice")
    public Map<String, Object> send(@Valid @RequestBody SendBody body, HttpServletRequest http) {
        guard.check(http);
        noticeService.send(new PlatformWalletNoticeRequest(
            body.toUserId(), body.noticeType(), body.title(), body.serviceName(),
            body.statusLabel(), body.summary(), body.rows(),
            body.actionLabel(), body.actionUrl(), body.orderId()));
        return Map.of("ok", true, "fromAccount", noticeService.senderUserId());
    }
}
