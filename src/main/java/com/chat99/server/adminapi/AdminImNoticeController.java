package com.chat99.server.adminapi;

import com.chat99.server.notify.PlatformWalletNoticeRequest;
import com.chat99.server.notify.PlatformWalletNoticeRow;
import com.chat99.server.notify.PlatformWalletNoticeService;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/im")
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AdminImNoticeController {

    private final PlatformWalletNoticeService noticeService;

    public AdminImNoticeController(PlatformWalletNoticeService noticeService) {
        this.noticeService = noticeService;
    }

    public record SendBody(
        String userUid,
        String toUserId,
        String noticeType,
        String title,
        String serviceName,
        String statusLabel,
        String summary,
        List<PlatformWalletNoticeRow> rows,
        String actionLabel,
        String actionUrl,
        String orderId) {}

    @PostMapping("/platform-wallet-notice")
    public Map<String, Object> send(Authentication auth, @RequestBody SendBody body) {
        AdminAccess.requirePermission(auth, "user.write");
        String toUserId = firstNonBlank(body.toUserId(), body.userUid());
        if (toUserId == null || toUserId.isBlank()) {
            throw new AdminApiException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "validation_error", "user_uid required");
        }
        String noticeType = body.noticeType() == null || body.noticeType().isBlank() ? "custom" : body.noticeType();
        String title = body.title() == null || body.title().isBlank() ? "支付助手通知" : body.title();
        noticeService.send(new PlatformWalletNoticeRequest(
            toUserId, noticeType, title, body.serviceName(),
            body.statusLabel(), body.summary(), body.rows(),
            body.actionLabel(), body.actionUrl(), body.orderId()));
        return Map.of("ok", true, "from_account", noticeService.senderUserId());
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }
}
