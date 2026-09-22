package com.chat99.server.adminapi;

import com.chat99.server.lifepayment.LifePaymentEnums.ActorType;
import com.chat99.server.lifepayment.LifePaymentEnums.OrderStatus;
import com.chat99.server.lifepayment.LifePaymentEnums.PlatformPayStatus;
import com.chat99.server.lifepayment.LifePaymentEnums.PluginStatus;
import com.chat99.server.lifepayment.LifePaymentEnums.ServiceType;
import com.chat99.server.lifepayment.LifePaymentEnums.TaskStatus;
import com.chat99.server.lifepayment.LifePaymentLogService;
import com.chat99.server.lifepayment.LifePaymentMobileDetailRepository;
import com.chat99.server.lifepayment.LifePaymentOrder;
import com.chat99.server.lifepayment.LifePaymentOrderRepository;
import com.chat99.server.lifepayment.LifePaymentOrderUpdateNoticeService;
import com.chat99.server.lifepayment.LifePaymentPaymentService;
import com.chat99.server.lifepayment.LifePaymentSupport;
import com.chat99.server.lifepayment.LifePaymentTask;
import com.chat99.server.lifepayment.LifePaymentTaskRepository;
import com.chat99.server.lifepayment.LifePaymentUtilityDetailRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminLifePaymentFailRefundService {

    private final LifePaymentOrderRepository orderRepository;
    private final LifePaymentTaskRepository taskRepository;
    private final LifePaymentMobileDetailRepository mobileDetailRepository;
    private final LifePaymentUtilityDetailRepository utilityDetailRepository;
    private final LifePaymentLogService lifePaymentLogService;
    private final LifePaymentPaymentService paymentService;
    private final AdminAuditService auditService;
    private final LifePaymentOrderUpdateNoticeService orderUpdateNoticeService;

    public AdminLifePaymentFailRefundService(LifePaymentOrderRepository orderRepository,
                                             LifePaymentTaskRepository taskRepository,
                                             LifePaymentMobileDetailRepository mobileDetailRepository,
                                             LifePaymentUtilityDetailRepository utilityDetailRepository,
                                             LifePaymentLogService lifePaymentLogService,
                                             LifePaymentPaymentService paymentService,
                                             AdminAuditService auditService,
                                             LifePaymentOrderUpdateNoticeService orderUpdateNoticeService) {
        this.orderRepository = orderRepository;
        this.taskRepository = taskRepository;
        this.mobileDetailRepository = mobileDetailRepository;
        this.utilityDetailRepository = utilityDetailRepository;
        this.lifePaymentLogService = lifePaymentLogService;
        this.paymentService = paymentService;
        this.auditService = auditService;
        this.orderUpdateNoticeService = orderUpdateNoticeService;
    }

    @Transactional
    public Map<String, Object> markFailedAndRefund(HttpServletRequest http, Authentication auth, String orderNo,
                                                   ReasonRequest body) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        LifePaymentOrder order = orderRepository.findByOrderNo(orderNo)
            .orElseThrow(() -> new AdminApiException(HttpStatus.NOT_FOUND, "not_found", "order_not_found"));
        if (order.getOrderStatus() == OrderStatus.success) {
            throw new AdminApiException(HttpStatus.CONFLICT, "conflict", "order already success");
        }
        if (order.getOrderStatus() == OrderStatus.cancelled) {
            throw new AdminApiException(HttpStatus.CONFLICT, "conflict", "order cancelled");
        }
        if (order.getOrderStatus() == OrderStatus.failed
            && order.getPlatformPayStatus() == PlatformPayStatus.refunded) {
            return toOrderItem(order);
        }

        String reason = body == null || body.reason() == null || body.reason().isBlank()
            ? "admin mark failed and refund" : body.reason().trim();
        Instant now = Instant.now();
        if (order.getPlatformPayStatus() == PlatformPayStatus.paid) {
            paymentService.refundIfNeeded(order.getUserId(), order.getId(),
                "生活缴费失败退款 " + orderNo + ": " + reason);
            order.setPlatformPayStatus(PlatformPayStatus.refunded);
        }
        order.setOrderStatus(OrderStatus.failed);
        order.setPluginStatus(PluginStatus.failed);
        orderRepository.save(order);

        List<LifePaymentTask> tasks = taskRepository.findByOrderNoAndStatusIn(orderNo,
            List.of(TaskStatus.ready, TaskStatus.running, TaskStatus.failed, TaskStatus.need_manual));
        for (LifePaymentTask task : tasks) {
            task.setStatus(TaskStatus.failed);
            task.setFinishedAt(now);
            task.setLockedBy(null);
            task.setLockedAt(null);
            task.setLastErrorCode("admin_fail_refund");
            task.setLastErrorMessage(reason);
            taskRepository.save(task);
        }

        if (order.getServiceType() == ServiceType.mobile) {
            mobileDetailRepository.findByOrderNo(orderNo).ifPresent(d -> {
                d.setRechargeStatus("充值失败");
                mobileDetailRepository.save(d);
            });
        } else {
            utilityDetailRepository.findByOrderNo(orderNo).ifPresent(d -> {
                d.setUtilityStatus("缴费失败");
                utilityDetailRepository.save(d);
            });
        }

        orderUpdateNoticeService.notify(order, "订单已失败，资金已退回");
        lifePaymentLogService.log(ActorType.admin, admin.username(), order.getServiceType(),
            orderNo, null, "mark_failed_refund", reason, body, null);
        auditService.log(http, admin.username(), "life_payment.order.fail_refund", order.getUserId(),
            Map.of("orderNo", orderNo, "reason", reason));
        return toOrderItem(order);
    }

    private Map<String, Object> toOrderItem(LifePaymentOrder order) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("order_no", order.getOrderNo());
        item.put("client_order_id", order.getClientOrderId());
        item.put("user_id", order.getUserId());
        item.put("service_type", order.getServiceType() == null ? null : order.getServiceType().name());
        item.put("amount", order.getAmount());
        item.put("pay_method", order.getPayMethod() == null ? null : order.getPayMethod().name());
        item.put("platform_pay_status",
            order.getPlatformPayStatus() == null ? null : order.getPlatformPayStatus().name());
        item.put("plugin_status", order.getPluginStatus() == null ? null : order.getPluginStatus().name());
        item.put("order_status", order.getOrderStatus() == null ? null : order.getOrderStatus().name());
        item.put("paid_at", LifePaymentSupport.formatTime(order.getPaidAt()));
        item.put("created_at", LifePaymentSupport.formatTime(order.getCreatedAt()));
        item.put("updated_at", LifePaymentSupport.formatTime(order.getUpdatedAt()));
        return item;
    }

    public record ReasonRequest(String reason) {}
}
