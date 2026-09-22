package com.chat99.server.lifepayment.yuanren;

import com.chat99.server.lifepayment.LifePaymentAccountService;
import com.chat99.server.lifepayment.LifePaymentEnums.ActorType;
import com.chat99.server.lifepayment.LifePaymentEnums.OrderStatus;
import com.chat99.server.lifepayment.LifePaymentEnums.PlatformPayStatus;
import com.chat99.server.lifepayment.LifePaymentEnums.PluginStatus;
import com.chat99.server.lifepayment.LifePaymentEnums.ServiceType;
import com.chat99.server.lifepayment.LifePaymentExceptions;
import com.chat99.server.lifepayment.LifePaymentLogService;
import com.chat99.server.lifepayment.LifePaymentMobileDetailRepository;
import com.chat99.server.lifepayment.LifePaymentOrder;
import com.chat99.server.lifepayment.LifePaymentOrderRepository;
import com.chat99.server.lifepayment.LifePaymentOrderUpdateNoticeService;
import com.chat99.server.lifepayment.LifePaymentPaymentService;
import com.chat99.server.lifepayment.LifePaymentSupport;
import com.chat99.server.lifepayment.LifePaymentUtilityDetailRepository;
import com.chat99.server.lifepayment.yuanren.YuanrenApiClient.CheckItem;
import com.chat99.server.lifepayment.yuanren.YuanrenApiClient.RechargeResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class YuanrenFulfillmentService {

    private static final Logger log = LoggerFactory.getLogger(YuanrenFulfillmentService.class);

    private final YuanrenProperties props;
    private final YuanrenApiClient client;
    private final LifePaymentOrderRepository orderRepository;
    private final LifePaymentMobileDetailRepository mobileDetailRepository;
    private final LifePaymentUtilityDetailRepository utilityDetailRepository;
    private final LifePaymentPaymentService paymentService;
    private final LifePaymentAccountService accountService;
    private final LifePaymentOrderUpdateNoticeService orderUpdateNoticeService;
    private final LifePaymentLogService logService;

    public YuanrenFulfillmentService(YuanrenProperties props,
                                     YuanrenApiClient client,
                                     LifePaymentOrderRepository orderRepository,
                                     LifePaymentMobileDetailRepository mobileDetailRepository,
                                     LifePaymentUtilityDetailRepository utilityDetailRepository,
                                     LifePaymentPaymentService paymentService,
                                     LifePaymentAccountService accountService,
                                     LifePaymentOrderUpdateNoticeService orderUpdateNoticeService,
                                     LifePaymentLogService logService) {
        this.props = props;
        this.client = client;
        this.orderRepository = orderRepository;
        this.mobileDetailRepository = mobileDetailRepository;
        this.utilityDetailRepository = utilityDetailRepository;
        this.paymentService = paymentService;
        this.accountService = accountService;
        this.orderUpdateNoticeService = orderUpdateNoticeService;
        this.logService = logService;
    }

    public boolean shouldFulfill(ServiceType type) {
        if (type != ServiceType.mobile && type != ServiceType.electric) {
            return false;
        }
        if (!props.isEnabled()) {
            return false;
        }
        if (!props.isConfigured()) {
            throw LifePaymentExceptions.badRequest("INVALID_INPUT",
                "大猿人上游已开启但未配置 base-url/userid/apikey/notify-url");
        }
        return true;
    }

    /** 事务提交后再调上游，避免长事务锁单。 */
    public void scheduleSubmit(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submitOrder(orderNo);
                }
            });
        } else {
            submitOrder(orderNo);
        }
    }

    public void submitOrder(String orderNo) {
        if (!props.isConfigured()) {
            log.warn("yuanren submit skipped: not configured orderNo={}", orderNo);
            return;
        }
        Optional<LifePaymentOrder> opt = orderRepository.findByOrderNo(orderNo);
        if (opt.isEmpty()) {
            return;
        }
        LifePaymentOrder order = opt.get();
        if (order.getOrderStatus() == OrderStatus.success
            || order.getOrderStatus() == OrderStatus.failed
            || order.getOrderStatus() == OrderStatus.cancelled) {
            return;
        }
        if (order.getPlatformPayStatus() != PlatformPayStatus.paid) {
            return;
        }

        try {
            Map<String, String> params = buildRechargeParams(order);
            RechargeResult result = client.recharge(params);
            if (result.ok()) {
                applySubmitted(order, result);
                return;
            }
            // 明确下单失败：退款并关单
            applySubmitRejected(order, result);
        } catch (Exception e) {
            log.error("yuanren submit uncertain orderNo={} err={}", orderNo, e.toString());
            orderRepository.findByOrderNo(orderNo).ifPresent(o -> {
                if (o.getOrderStatus() == OrderStatus.paid || o.getOrderStatus() == OrderStatus.created) {
                    o.setOrderStatus(OrderStatus.running);
                    o.setPluginStatus(PluginStatus.running);
                    orderRepository.save(o);
                    orderUpdateNoticeService.notify(o, "上游受理中，请稍后查看结果");
                }
            });
            logService.log(ActorType.system, "yuanren", order.getServiceType(), orderNo, null,
                "yuanren_submit_uncertain", e.getMessage(), null, null);
        }
    }

    @Transactional
    public String handleNotify(Map<String, String> form) {
        if (!props.isConfigured()) {
            return "disabled";
        }
        if (!YuanrenSignSupport.verify(form, props.getApikey())) {
            log.warn("yuanren notify bad sign out_trade_num={}", form.get("out_trade_num"));
            return "fail";
        }
        String outTradeNum = form.get("out_trade_num");
        String state = form.get("state");
        if (outTradeNum == null || outTradeNum.isBlank()) {
            return "fail";
        }
        applyUpstreamState(outTradeNum, state, form.get("order_number"), form.get("charge_kami"),
            form.get("voucher"), form.get("charge_amount"), form.get("remark"), "notify");
        return "success";
    }

    public void pollPendingOrders() {
        if (!props.isConfigured() || !props.isPollEnabled()) {
            return;
        }
        List<LifePaymentOrder> pending = orderRepository.findPendingYuanren(
            List.of(ServiceType.mobile, ServiceType.electric),
            List.of(OrderStatus.paid, OrderStatus.running, OrderStatus.processing),
            PageRequest.of(0, props.getPollBatchSize()));
        for (LifePaymentOrder order : pending) {
            try {
                CheckItem item = client.checkOne(order.getOrderNo());
                applyUpstreamState(order.getOrderNo(), item.state(), item.orderNumber(), item.chargeKami(),
                    null, item.chargeAmount(), null, "poll");
            } catch (Exception e) {
                log.warn("yuanren poll failed orderNo={} err={}", order.getOrderNo(), e.toString());
            }
        }
    }

    private Map<String, String> buildRechargeParams(LifePaymentOrder order) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("out_trade_num", order.getOrderNo());
        String amountKey = amountKey(order.getAmount());
        params.put("amount", amountKey);

        if (order.getServiceType() == ServiceType.mobile) {
            var detail = mobileDetailRepository.findByOrderNo(order.getOrderNo())
                .orElseThrow(() -> LifePaymentExceptions.notFound("order_not_found"));
            String productId = resolveMobileProductId(amountKey);
            params.put("product_id", productId);
            params.put("mobile", detail.getPhone());
            return params;
        }
        if (order.getServiceType() == ServiceType.electric) {
            var detail = utilityDetailRepository.findByOrderNo(order.getOrderNo())
                .orElseThrow(() -> LifePaymentExceptions.notFound("order_not_found"));
            String productId = resolveElectricProductId(amountKey);
            params.put("product_id", productId);
            params.put("mobile", detail.getAccountNo());
            if (detail.getCityName() != null && !detail.getCityName().isBlank()) {
                // 文档 area=省份；无省字段时先传城市名，渠道侧可再调
                params.put("area", detail.getCityName());
                params.put("city", detail.getCityName());
            }
            return params;
        }
        throw LifePaymentExceptions.badRequest("INVALID_INPUT", "yuanren unsupported service");
    }

    private String resolveMobileProductId(String amountKey) {
        String id = props.getMobileProductIds().get(amountKey);
        if (id == null || id.isBlank()) {
            throw LifePaymentExceptions.badRequest("INVALID_INPUT",
                "未配置话费面额 " + amountKey + " 的大猿人 product_id");
        }
        return id.trim();
    }

    private String resolveElectricProductId(String amountKey) {
        String mapped = props.getElectricProductIds().get(amountKey);
        if (mapped != null && !mapped.isBlank()) {
            return mapped.trim();
        }
        if (props.getElectricProductId() != null && !props.getElectricProductId().isBlank()) {
            return props.getElectricProductId().trim();
        }
        throw LifePaymentExceptions.badRequest("INVALID_INPUT", "未配置电费大猿人 product_id");
    }

    private void applySubmitted(LifePaymentOrder order, RechargeResult result) {
        order.setOrderStatus(OrderStatus.running);
        order.setPluginStatus(PluginStatus.running);
        orderRepository.save(order);
        if (order.getServiceType() == ServiceType.mobile) {
            mobileDetailRepository.findByOrderNo(order.getOrderNo()).ifPresent(d -> {
                d.setAlipayTradeNo(result.orderNumber());
                d.setRechargeStatus("上游已受理");
                if (result.title() != null) {
                    d.setReceipt(result.title());
                }
                mobileDetailRepository.save(d);
            });
        } else {
            utilityDetailRepository.findByOrderNo(order.getOrderNo()).ifPresent(d -> {
                d.setAlipayTradeNo(result.orderNumber());
                d.setUtilityStatus("上游已受理");
                if (result.title() != null) {
                    d.setReceipt(result.title());
                }
                utilityDetailRepository.save(d);
            });
        }
        orderUpdateNoticeService.notify(order, "已提交上游充值");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("order_number", result.orderNumber());
        meta.put("total_price", result.totalPrice());
        meta.put("title", result.title());
        logService.log(ActorType.system, "yuanren", order.getServiceType(), order.getOrderNo(), null,
            "yuanren_submit_ok", result.errmsg(), meta, null);
    }

    private void applySubmitRejected(LifePaymentOrder order, RechargeResult result) {
        order.setOrderStatus(OrderStatus.failed);
        order.setPluginStatus(PluginStatus.failed);
        order.setPlatformPayStatus(PlatformPayStatus.refunded);
        orderRepository.save(order);
        paymentService.refundIfNeeded(order.getUserId(), order.getId(),
            "大猿人下单失败退款: " + safe(result.errmsg()));
        orderUpdateNoticeService.notify(order, "上游下单失败，已退款");
        logService.log(ActorType.system, "yuanren", order.getServiceType(), order.getOrderNo(), null,
            "yuanren_submit_reject", result.errmsg(), Map.of("errno", safe(result.errno())), null);
    }

    @Transactional
    public void applyUpstreamState(String outTradeNum, String stateRaw, String upstreamOrderNo,
                                      String kami, String voucher, String chargeAmount, String remark,
                                      String source) {
        Optional<LifePaymentOrder> opt = orderRepository.findByOrderNo(outTradeNum);
        if (opt.isEmpty()) {
            log.warn("yuanren {} unknown out_trade_num={}", source, outTradeNum);
            return;
        }
        LifePaymentOrder order = opt.get();
        if (order.getOrderStatus() == OrderStatus.success
            || order.getOrderStatus() == OrderStatus.cancelled) {
            return;
        }
        int state = parseState(stateRaw);
        // -1 取消 / 2 失败 → 失败退款；1 成功；0 充值中；3 部分成功按成功处理（可再细化）
        if (state == 0) {
            if (order.getOrderStatus() != OrderStatus.running && order.getOrderStatus() != OrderStatus.processing) {
                order.setOrderStatus(OrderStatus.running);
                order.setPluginStatus(PluginStatus.running);
                orderRepository.save(order);
            }
            return;
        }
        if (state == 1 || state == 3) {
            markSuccess(order, upstreamOrderNo, kami, voucher, chargeAmount, remark, source);
            return;
        }
        if (state == -1 || state == 2) {
            markFailed(order, upstreamOrderNo, kami, voucher, remark, source);
        }
    }

    private void markSuccess(LifePaymentOrder order, String upstreamOrderNo, String kami, String voucher,
                             String chargeAmount, String remark, String source) {
        order.setOrderStatus(OrderStatus.success);
        order.setPluginStatus(PluginStatus.paid_success);
        orderRepository.save(order);
        BigDecimal paid = parseMoney(chargeAmount);
        if (order.getServiceType() == ServiceType.mobile) {
            mobileDetailRepository.findByOrderNo(order.getOrderNo()).ifPresent(d -> {
                if (upstreamOrderNo != null) {
                    d.setAlipayTradeNo(upstreamOrderNo);
                }
                d.setRechargeStatus(defaultText(remark, "充值成功"));
                if (voucher != null && !voucher.isBlank()) {
                    d.setReceipt(voucher);
                } else if (kami != null && !kami.isBlank()) {
                    d.setReceipt(kami);
                }
                if (paid != null) {
                    d.setPaidAmount(paid);
                }
                mobileDetailRepository.save(d);
                accountService.markSuccess(ServiceType.mobile, d.getPhone(), null, "", null, "",
                    d.getOwnerLastChar(), null, order.getOrderNo());
            });
        } else {
            utilityDetailRepository.findByOrderNo(order.getOrderNo()).ifPresent(d -> {
                if (upstreamOrderNo != null) {
                    d.setAlipayTradeNo(upstreamOrderNo);
                }
                d.setUtilityStatus(defaultText(remark, "缴费成功"));
                if (voucher != null && !voucher.isBlank()) {
                    d.setReceipt(voucher);
                } else if (kami != null && !kami.isBlank()) {
                    d.setReceipt(kami);
                }
                if (paid != null) {
                    d.setPaidAmount(paid);
                }
                utilityDetailRepository.save(d);
                accountService.markSuccess(order.getServiceType(), d.getAccountNo(), d.getCityName(),
                    LifePaymentSupport.blankToEmpty(d.getCityCode()), d.getProviderName(),
                    LifePaymentSupport.blankToEmpty(d.getProviderCode()), null, d.getUserAddress(),
                    order.getOrderNo());
            });
        }
        orderUpdateNoticeService.notify(order,
            order.getServiceType() == ServiceType.mobile ? "充值成功" : "缴费成功");
        logService.log(ActorType.system, "yuanren", order.getServiceType(), order.getOrderNo(), null,
            "yuanren_success", source, Map.of("state", "1", "order_number", safe(upstreamOrderNo)), null);
    }

    private void markFailed(LifePaymentOrder order, String upstreamOrderNo, String kami, String voucher,
                            String remark, String source) {
        if (order.getOrderStatus() == OrderStatus.failed
            && order.getPlatformPayStatus() == PlatformPayStatus.refunded) {
            return;
        }
        order.setOrderStatus(OrderStatus.failed);
        order.setPluginStatus(PluginStatus.failed);
        order.setPlatformPayStatus(PlatformPayStatus.refunded);
        orderRepository.save(order);
        paymentService.refundIfNeeded(order.getUserId(), order.getId(),
            "大猿人失败退款: " + defaultText(remark, source));
        if (order.getServiceType() == ServiceType.mobile) {
            mobileDetailRepository.findByOrderNo(order.getOrderNo()).ifPresent(d -> {
                if (upstreamOrderNo != null) {
                    d.setAlipayTradeNo(upstreamOrderNo);
                }
                d.setRechargeStatus(defaultText(remark, "充值失败"));
                if (voucher != null) {
                    d.setReceipt(voucher);
                } else if (kami != null) {
                    d.setReceipt(kami);
                }
                mobileDetailRepository.save(d);
            });
        } else {
            utilityDetailRepository.findByOrderNo(order.getOrderNo()).ifPresent(d -> {
                if (upstreamOrderNo != null) {
                    d.setAlipayTradeNo(upstreamOrderNo);
                }
                d.setUtilityStatus(defaultText(remark, "缴费失败"));
                if (voucher != null) {
                    d.setReceipt(voucher);
                } else if (kami != null) {
                    d.setReceipt(kami);
                }
                utilityDetailRepository.save(d);
            });
        }
        orderUpdateNoticeService.notify(order, "上游失败，已退款");
        logService.log(ActorType.system, "yuanren", order.getServiceType(), order.getOrderNo(), null,
            "yuanren_failed", source, Map.of("remark", safe(remark)), null);
    }

    private static String amountKey(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    private static int parseState(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static BigDecimal parseMoney(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String defaultText(String v, String dft) {
        return v == null || v.isBlank() ? dft : v.trim();
    }

    private static String safe(String v) {
        return v == null ? "" : v;
    }
}
