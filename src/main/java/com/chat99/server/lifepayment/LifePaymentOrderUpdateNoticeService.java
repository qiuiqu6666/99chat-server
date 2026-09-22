package com.chat99.server.lifepayment;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImRestException;
import com.chat99.server.im.ImUserIdService;
import com.chat99.server.lifepayment.LifePaymentEnums.OrderStatus;
import com.chat99.server.lifepayment.LifePaymentEnums.PayMethod;
import com.chat99.server.lifepayment.LifePaymentEnums.ServiceType;
import com.chat99.server.notify.PlatformWalletNoticeProperties;
import com.chat99.server.notify.PlatformWalletNoticeRequest;
import com.chat99.server.notify.PlatformWalletNoticeRow;
import com.chat99.server.notify.PlatformWalletNoticeService;
import com.chat99.server.push.PushMessage;
import com.chat99.server.push.PushService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class LifePaymentOrderUpdateNoticeService {

    public static final String CUSTOM_TYPE = "life_payment_order_update";

    private static final Logger log = LoggerFactory.getLogger(LifePaymentOrderUpdateNoticeService.class);

    private final LifePaymentProperties properties;
    private final PlatformWalletNoticeProperties walletNoticeProperties;
    private final PlatformWalletNoticeService walletNoticeService;
    private final ImAdminClient imAdmin;
    private final ImUserIdService imUserIdService;
    private final PushService pushService;
    private final LifePaymentMobileDetailRepository mobileDetailRepository;
    private final LifePaymentUtilityDetailRepository utilityDetailRepository;

    public LifePaymentOrderUpdateNoticeService(LifePaymentProperties properties,
                                               PlatformWalletNoticeProperties walletNoticeProperties,
                                               PlatformWalletNoticeService walletNoticeService,
                                               ImAdminClient imAdmin,
                                               ImUserIdService imUserIdService,
                                               PushService pushService,
                                               LifePaymentMobileDetailRepository mobileDetailRepository,
                                               LifePaymentUtilityDetailRepository utilityDetailRepository) {
        this.properties = properties;
        this.walletNoticeProperties = walletNoticeProperties;
        this.walletNoticeService = walletNoticeService;
        this.imAdmin = imAdmin;
        this.imUserIdService = imUserIdService;
        this.pushService = pushService;
        this.mobileDetailRepository = mobileDetailRepository;
        this.utilityDetailRepository = utilityDetailRepository;
    }

    public void notify(LifePaymentOrder order, String message) {
        if (!properties.isOrderUpdateNotifyEnabled() || order == null) {
            return;
        }
        String userId = order.getUserId();
        if (userId == null || userId.isBlank()) {
            return;
        }
        String title = buildTitle(order);
        String summary = message != null && !message.isBlank() ? message : defaultMessage(order);
        walletNoticeService.ensureFriend(userId);
        Map<String, Object> data = toImData(order, summary);
        try {
            imAdmin.sendCustomC2c(walletNoticeProperties.senderUserId(), imUserIdService.toIm(userId), data, title);
            log.info("life payment order update im sent userId={} orderNo={} status={}",
                userId, order.getOrderNo(), order.getOrderStatus());
        } catch (ImRestException e) {
            log.warn("life payment order update im failed userId={} orderNo={} code={} msg={}",
                userId, order.getOrderNo(), e.imErrorCode(), e.getMessage());
        } catch (Exception e) {
            log.warn("life payment order update im failed userId={} orderNo={} err={}",
                userId, order.getOrderNo(), e.getMessage());
        }
        sendOfflinePush(userId, title, summary, order);
        sendWalletCardIfTerminal(order, summary);
    }

    private void sendWalletCardIfTerminal(LifePaymentOrder order, String summary) {
        OrderStatus status = order.getOrderStatus();
        if (status != OrderStatus.success && status != OrderStatus.failed && status != OrderStatus.cancelled) {
            return;
        }
        String noticeType = "lifePayment";
        String title;
        String statusLabel;
        if (status == OrderStatus.success) {
            title = order.getServiceType() == ServiceType.mobile ? "话费充值成功" : "生活缴费成功";
            statusLabel = "成功";
        } else if (status == OrderStatus.failed) {
            title = order.getServiceType() == ServiceType.mobile ? "话费充值失败" : "生活缴费失败";
            statusLabel = "失败";
        } else {
            title = "生活缴费订单已取消";
            statusLabel = "已取消";
        }
        List<PlatformWalletNoticeRow> rows = buildWalletRows(order);
        walletNoticeService.send(new PlatformWalletNoticeRequest(
            order.getUserId(),
            noticeType,
            title,
            walletNoticeProperties.serviceName(),
            statusLabel,
            summary,
            rows,
            "查看详情",
            null,
            order.getOrderNo()
        ));
    }

    private List<PlatformWalletNoticeRow> buildWalletRows(LifePaymentOrder order) {
        List<PlatformWalletNoticeRow> rows = new ArrayList<>();
        rows.add(row("业务类型", serviceLabel(order.getServiceType()), false));
        rows.add(row("金额", LifePaymentSupport.formatAmount(order.getAmount()) + " "
            + payMethodLabel(order.getPayMethod()), true));
        if (order.getServiceType() == ServiceType.mobile) {
            mobileDetailRepository.findByOrderNo(order.getOrderNo()).ifPresent(d ->
                rows.add(row("手机号", d.getPhone(), false)));
        } else {
            utilityDetailRepository.findByOrderNo(order.getOrderNo()).ifPresent(d -> {
                rows.add(row("户号", d.getAccountNo(), false));
                if (d.getProviderName() != null && !d.getProviderName().isBlank()) {
                    rows.add(row("缴费单位", d.getProviderName(), false));
                }
            });
        }
        rows.add(row("订单号", order.getOrderNo(), false));
        rows.add(row("时间", LifePaymentSupport.formatTime(order.getUpdatedAt()), false));
        return rows;
    }

    private static PlatformWalletNoticeRow row(String label, String value, boolean emphasize) {
        return new PlatformWalletNoticeRow(label, value, emphasize);
    }

    private static String payMethodLabel(PayMethod payMethod) {
        return switch (payMethod) {
            case coin_99 -> "99币";
            case usdt -> "USDT";
        };
    }

    private void sendOfflinePush(String userId, String title, String summary, LifePaymentOrder order) {
        PushMessage message = PushMessage.of(title, summary)
            .withData("type", CUSTOM_TYPE)
            .withData("orderNo", order.getOrderNo())
            .withData("orderStatus", order.getOrderStatus().name())
            .withData("serviceType", order.getServiceType().name());
        pushService.sendToUser(userId, message);
    }

    private Map<String, Object> toImData(LifePaymentOrder order, String summary) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("customType", CUSTOM_TYPE);
        data.put("businessID", CUSTOM_TYPE);
        data.put("version", 1);
        data.put("order_no", order.getOrderNo());
        data.put("order_status", order.getOrderStatus().name());
        data.put("plugin_status", order.getPluginStatus().name());
        data.put("service_type", order.getServiceType().name());
        data.put("amount", LifePaymentSupport.formatAmount(order.getAmount()));
        data.put("pay_method", order.getPayMethod().name());
        data.put("message", summary);
        data.put("updated_at", LifePaymentSupport.formatTime(order.getUpdatedAt()));
        if (order.getQueryNo() != null && !order.getQueryNo().isBlank()) {
            data.put("query_no", order.getQueryNo());
        }
        return data;
    }

    private static String buildTitle(LifePaymentOrder order) {
        String service = serviceLabel(order.getServiceType());
        String status = statusLabel(order.getOrderStatus());
        return service + " · " + status;
    }

    private static String defaultMessage(LifePaymentOrder order) {
        return switch (order.getOrderStatus()) {
            case paid -> "订单已创建，等待插件执行";
            case running -> "插件正在处理您的订单";
            case processing -> "缴费处理中，请稍候";
            case cashier_confirm -> "已到支付确认环节";
            case success -> order.getServiceType() == ServiceType.mobile ? "充值成功" : "缴费成功";
            case failed -> "订单处理失败";
            case cancelled -> "订单已取消";
            case need_manual -> "订单需人工处理，客服将尽快跟进";
            case need_owner_last_char -> "请补充机主姓名最后一个字后重试";
            default -> "订单状态已更新";
        };
    }

    private static String serviceLabel(ServiceType type) {
        return switch (type) {
            case mobile -> "手机充值";
            case water -> "水费";
            case electric -> "电费";
            case gas -> "燃气费";
        };
    }

    private static String statusLabel(OrderStatus status) {
        return switch (status) {
            case paid -> "待执行";
            case running -> "执行中";
            case processing -> "处理中";
            case cashier_confirm -> "待确认";
            case success -> "成功";
            case failed -> "失败";
            case cancelled -> "已取消";
            case need_manual -> "待人工";
            case need_owner_last_char -> "待补充信息";
            default -> status.name();
        };
    }
}
