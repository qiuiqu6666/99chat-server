package com.chat99.server.notify;

import java.util.List;

public record PlatformWalletNoticeRequest(
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
