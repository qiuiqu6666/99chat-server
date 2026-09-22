package com.chat99.server.lifepayment;

import com.chat99.server.lifepayment.LifePaymentEnums.ActorType;
import com.chat99.server.lifepayment.LifePaymentEnums.OrderStatus;
import com.chat99.server.lifepayment.LifePaymentEnums.PayMethod;
import com.chat99.server.lifepayment.LifePaymentEnums.PlatformPayStatus;
import com.chat99.server.lifepayment.LifePaymentEnums.PluginStatus;
import com.chat99.server.lifepayment.LifePaymentEnums.QueryStatus;
import com.chat99.server.lifepayment.LifePaymentEnums.ServiceType;
import com.chat99.server.lifepayment.LifePaymentEnums.TaskAction;
import com.chat99.server.lifepayment.LifePaymentEnums.TaskStatus;
import com.chat99.server.lifepayment.yuanren.YuanrenFulfillmentService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LifePaymentOrderService {

    private final LifePaymentProperties properties;
    private final LifePaymentCatalogService catalogService;
    private final LifePaymentAccountService accountService;
    private final LifePaymentPaymentService paymentService;
    private final LifePaymentTaskService taskService;
    private final LifePaymentLogService logService;
    private final LifePaymentOrderRepository orderRepository;
    private final LifePaymentMobileDetailRepository mobileDetailRepository;
    private final LifePaymentUtilityDetailRepository utilityDetailRepository;
    private final LifePaymentUtilityQueryRepository queryRepository;
    private final LifePaymentProviderRepository providerRepository;
    private final LifePaymentAmountOptionRepository amountOptionRepository;
    private final LifePaymentTaskRepository taskRepository;
    private final LifePaymentOrderUpdateNoticeService orderUpdateNoticeService;
    private final YuanrenFulfillmentService yuanrenFulfillmentService;

    public LifePaymentOrderService(LifePaymentProperties properties,
                                   LifePaymentCatalogService catalogService,
                                   LifePaymentAccountService accountService,
                                   LifePaymentPaymentService paymentService,
                                   LifePaymentTaskService taskService,
                                   LifePaymentLogService logService,
                                   LifePaymentOrderRepository orderRepository,
                                   LifePaymentMobileDetailRepository mobileDetailRepository,
                                   LifePaymentUtilityDetailRepository utilityDetailRepository,
                                   LifePaymentUtilityQueryRepository queryRepository,
                                   LifePaymentProviderRepository providerRepository,
                                   LifePaymentAmountOptionRepository amountOptionRepository,
                                   LifePaymentTaskRepository taskRepository,
                                   LifePaymentOrderUpdateNoticeService orderUpdateNoticeService,
                                   YuanrenFulfillmentService yuanrenFulfillmentService) {
        this.properties = properties;
        this.catalogService = catalogService;
        this.accountService = accountService;
        this.paymentService = paymentService;
        this.taskService = taskService;
        this.logService = logService;
        this.orderRepository = orderRepository;
        this.mobileDetailRepository = mobileDetailRepository;
        this.utilityDetailRepository = utilityDetailRepository;
        this.queryRepository = queryRepository;
        this.providerRepository = providerRepository;
        this.amountOptionRepository = amountOptionRepository;
        this.taskRepository = taskRepository;
        this.orderUpdateNoticeService = orderUpdateNoticeService;
        this.yuanrenFulfillmentService = yuanrenFulfillmentService;
    }

    public Map<String, Object> home(String userId) {
        catalogService.ensureEnabled();
        List<Map<String, Object>> services = ServiceType.values().length == 0 ? List.of() : java.util.Arrays.stream(ServiceType.values())
            .map(type -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("service_type", type.name());
                item.put("title", LifePaymentSupport.serviceTitle(type));
                item.put("subtitle", LifePaymentSupport.serviceSubtitle(type));
                item.put("enabled", properties.isEnabled());
                return item;
            }).toList();

        Instant monthStart = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
            .withDayOfMonth(1).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant();
        BigDecimal monthPaid = orderRepository.sumMonthPaid(userId, monthStart,
            List.of(OrderStatus.cancelled, OrderStatus.failed, OrderStatus.created));
        List<Map<String, Object>> recentOrders = orderRepository.findTop10ByUserIdOrderByCreatedAtDesc(userId)
            .stream().map(this::toRecentOrder).toList();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("city_name", "");
        resp.put("month_paid_amount", LifePaymentSupport.formatAmount(monthPaid == null ? BigDecimal.ZERO : monthPaid));
        resp.put("services", services);
        resp.put("recent_orders", recentOrders);
        return resp;
    }

    @Transactional
    public Map<String, Object> createMobileOrder(String userId, Map<String, Object> body) {
        catalogService.ensureEnabled();
        String clientOrderId = requireText(body, "client_order_id");
        Optional<LifePaymentOrder> existing = orderRepository.findByUserIdAndClientOrderId(userId, clientOrderId);
        if (existing.isPresent()) {
            return toCreateOrderResponse(existing.get(), "订单已存在");
        }

        String phone = LifePaymentSupport.normalizePhone(requireText(body, "phone"));
        BigDecimal amount = LifePaymentSupport.requirePositiveAmount(asDecimal(body.get("amount")));
        validateMobileAmount(amount);
        PayMethod payMethod = PayMethod.require(asText(body.get("pay_method")));
        String payPassword = asText(body.get("pay_password"));
        String ownerLastChar = asText(body.get("owner_last_char"));

        boolean first = !accountService.isMobileVerified(phone);
        if (first && (ownerLastChar == null || ownerLastChar.isBlank())) {
            throw LifePaymentExceptions.badRequest("owner_last_char_required");
        }
        if (ownerLastChar != null && !ownerLastChar.isBlank() && ownerLastChar.trim().length() > 4) {
            throw LifePaymentExceptions.badRequest("INVALID_INPUT");
        }

        LifePaymentOrder order = new LifePaymentOrder();
        order.setOrderNo(LifePaymentSupport.newOrderNo(ServiceType.mobile));
        order.setClientOrderId(clientOrderId);
        order.setUserId(userId);
        order.setServiceType(ServiceType.mobile);
        order.setAmount(amount);
        order.setPayMethod(payMethod);
        order.setPlatformPayStatus(PlatformPayStatus.pending);
        order.setPluginStatus(PluginStatus.ready);
        order.setOrderStatus(OrderStatus.created);
        try {
            order = orderRepository.saveAndFlush(order);
        } catch (DataIntegrityViolationException e) {
            return orderRepository.findByUserIdAndClientOrderId(userId, clientOrderId)
                .map(o -> toCreateOrderResponse(o, "订单已存在"))
                .orElseThrow(() -> LifePaymentExceptions.conflict("duplicate_client_order_id"));
        }

        LifePaymentMobileDetail detail = new LifePaymentMobileDetail();
        detail.setOrderNo(order.getOrderNo());
        detail.setPhone(phone);
        detail.setOwnerLastChar(ownerLastChar == null || ownerLastChar.isBlank() ? null : ownerLastChar.trim());
        detail.setFirstRecharge(first);
        detail.setCarrierName(LifePaymentSupport.guessCarrier(phone));
        mobileDetailRepository.save(detail);

        paymentService.charge(userId, payMethod, amount, payPassword, order.getId(),
            "生活缴费-手机充值 " + phone);
        Instant now = Instant.now();
        order.setPlatformPayStatus(PlatformPayStatus.paid);
        order.setOrderStatus(OrderStatus.paid);
        order.setPaidAt(now);
        orderRepository.save(order);

        if (yuanrenFulfillmentService.shouldFulfill(ServiceType.mobile)) {
            orderUpdateNoticeService.notify(order, "订单已支付，正在提交上游充值");
            yuanrenFulfillmentService.scheduleSubmit(order.getOrderNo());
            logService.log(ActorType.user, userId, ServiceType.mobile, order.getOrderNo(), null,
                "create_order", "手机充值订单已创建(大猿人)", sanitize(body), null);
            return toCreateOrderResponse(order, "订单已创建，等待上游充值");
        }

        orderUpdateNoticeService.notify(order, "订单已创建，等待插件执行");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task_action", TaskAction.recharge.name());
        payload.put("service_type", ServiceType.mobile.name());
        payload.put("order_no", order.getOrderNo());
        payload.put("account_no", phone);
        payload.put("amount", amount);
        payload.put("payment_status", "paid");
        if (detail.getOwnerLastChar() != null) {
            payload.put("owner_last_char", detail.getOwnerLastChar());
        }
        LifePaymentTask task = taskService.enqueue(order.getOrderNo(), null, ServiceType.mobile,
            TaskAction.recharge, "paid", payload);

        logService.log(ActorType.user, userId, ServiceType.mobile, order.getOrderNo(), task.getTaskNo(),
            "create_order", "手机充值订单已创建", sanitize(body), null);

        Map<String, Object> resp = toCreateOrderResponse(order, "订单已创建，等待插件执行");
        resp.put("task_no", task.getTaskNo());
        return resp;
    }

    @Transactional
    public Map<String, Object> createUtilityQuery(String userId, Map<String, Object> body) {
        catalogService.ensureEnabled();
        ServiceType serviceType = ServiceType.requireUtility(asText(body.get("service_type")));
        String cityName = requireText(body, "city_name");
        String cityCode = LifePaymentSupport.blankToEmpty(asText(body.get("city_code")));
        String providerName = requireText(body, "provider_name");
        String providerCode = LifePaymentSupport.blankToEmpty(asText(body.get("provider_code")));
        String accountNo = requireText(body, "account_no");

        if (providerCode.isBlank()) {
            providerCode = LifePaymentSupport.providerCodeOf(serviceType, cityCode, cityName, providerName);
        }
        ensureProvider(serviceType, cityName, cityCode, providerName, providerCode);
        taskService.assertAccountAvailable(serviceType, accountNo);

        LifePaymentUtilityQuery query = new LifePaymentUtilityQuery();
        query.setQueryNo(LifePaymentSupport.newQueryNo());
        query.setUserId(userId);
        query.setServiceType(serviceType);
        query.setCityName(cityName);
        query.setCityCode(cityCode);
        query.setProviderName(providerName);
        query.setProviderCode(providerCode);
        query.setAccountNo(accountNo);
        query.setQueryStatus(QueryStatus.ready);
        query.setPluginStatus(PluginStatus.ready);
        queryRepository.save(query);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task_action", TaskAction.query.name());
        payload.put("query_no", query.getQueryNo());
        payload.put("service_type", serviceType.name());
        payload.put("city_name", cityName);
        payload.put("city_code", cityCode);
        payload.put("provider_name", providerName);
        payload.put("provider_code", providerCode);
        payload.put("account_no", accountNo);
        LifePaymentTask task = taskService.enqueue(null, query.getQueryNo(), serviceType,
            TaskAction.query, "none", payload);

        logService.log(ActorType.user, userId, serviceType, null, task.getTaskNo(),
            "create_query", "户号查询任务已创建", body, null);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("query_no", query.getQueryNo());
        resp.put("service_type", serviceType.name());
        resp.put("query_status", query.getQueryStatus().name());
        resp.put("task_no", task.getTaskNo());
        resp.put("message", "户号查询任务已创建");
        return resp;
    }

    public Map<String, Object> getUtilityQuery(String userId, String queryNo) {
        catalogService.ensureEnabled();
        LifePaymentUtilityQuery query = queryRepository.findByUserIdAndQueryNo(userId, queryNo)
            .orElseThrow(() -> LifePaymentExceptions.notFound("order_not_found"));
        maybeExpireQuery(query);
        return toQueryResponse(query);
    }

    @Transactional
    public Map<String, Object> createUtilityOrder(String userId, Map<String, Object> body) {
        catalogService.ensureEnabled();
        String clientOrderId = requireText(body, "client_order_id");
        Optional<LifePaymentOrder> existing = orderRepository.findByUserIdAndClientOrderId(userId, clientOrderId);
        if (existing.isPresent()) {
            return toCreateOrderResponse(existing.get(), "订单已存在");
        }

        String queryNo = requireText(body, "query_no");
        LifePaymentUtilityQuery query = queryRepository.findByUserIdAndQueryNo(userId, queryNo)
            .orElseThrow(() -> LifePaymentExceptions.notFound("order_not_found"));
        maybeExpireQuery(query);
        if (query.getQueryStatus() == QueryStatus.expired) {
            throw LifePaymentExceptions.badRequest("INVALID_INPUT");
        }
        if (query.getQueryStatus() != QueryStatus.query_success && query.getQueryStatus() != QueryStatus.confirmed) {
            throw LifePaymentExceptions.badRequest("INVALID_INPUT");
        }

        ServiceType serviceType = ServiceType.requireUtility(asText(body.get("service_type")));
        if (serviceType != query.getServiceType()) {
            throw LifePaymentExceptions.badRequest("INVALID_INPUT");
        }
        String cityName = requireText(body, "city_name");
        String cityCode = LifePaymentSupport.blankToEmpty(asText(body.get("city_code")));
        String providerName = requireText(body, "provider_name");
        String providerCode = LifePaymentSupport.blankToEmpty(asText(body.get("provider_code")));
        String accountNo = requireText(body, "account_no");
        String confirmedAddress = requireText(body, "confirmed_user_address");
        BigDecimal amount = LifePaymentSupport.requirePositiveAmount(asDecimal(body.get("amount")));
        PayMethod payMethod = PayMethod.require(asText(body.get("pay_method")));
        String payPassword = asText(body.get("pay_password"));

        if (!LifePaymentSupport.eqIgnoreBlank(accountNo, query.getAccountNo())
            || !LifePaymentSupport.eqIgnoreBlank(cityName, query.getCityName())
            || !LifePaymentSupport.eqIgnoreBlank(providerName, query.getProviderName())) {
            throw LifePaymentExceptions.badRequest("INVALID_INPUT");
        }
        if (query.getUserAddress() != null && !query.getUserAddress().isBlank()
            && !LifePaymentSupport.eqIgnoreBlank(confirmedAddress, query.getUserAddress())) {
            throw LifePaymentExceptions.badRequest("INVALID_INPUT");
        }
        if (!cityCode.isBlank() && !LifePaymentSupport.eqIgnoreBlank(cityCode, query.getCityCode())) {
            throw LifePaymentExceptions.badRequest("INVALID_INPUT");
        }
        if (!providerCode.isBlank() && !LifePaymentSupport.eqIgnoreBlank(providerCode, query.getProviderCode())) {
            throw LifePaymentExceptions.badRequest("INVALID_INPUT");
        }
        taskService.assertAccountAvailable(serviceType, accountNo);

        LifePaymentOrder order = new LifePaymentOrder();
        order.setOrderNo(LifePaymentSupport.newOrderNo(serviceType));
        order.setClientOrderId(clientOrderId);
        order.setUserId(userId);
        order.setServiceType(serviceType);
        order.setAmount(amount);
        order.setPayMethod(payMethod);
        order.setPlatformPayStatus(PlatformPayStatus.pending);
        order.setPluginStatus(PluginStatus.ready);
        order.setOrderStatus(OrderStatus.created);
        order.setQueryNo(queryNo);
        try {
            order = orderRepository.saveAndFlush(order);
        } catch (DataIntegrityViolationException e) {
            return orderRepository.findByUserIdAndClientOrderId(userId, clientOrderId)
                .map(o -> toCreateOrderResponse(o, "订单已存在"))
                .orElseThrow(() -> LifePaymentExceptions.conflict("duplicate_client_order_id"));
        }

        LifePaymentUtilityDetail detail = new LifePaymentUtilityDetail();
        detail.setOrderNo(order.getOrderNo());
        detail.setServiceType(serviceType);
        detail.setCityName(cityName);
        detail.setCityCode(cityCode.isBlank() ? query.getCityCode() : cityCode);
        detail.setProviderName(providerName);
        detail.setProviderCode(providerCode.isBlank() ? query.getProviderCode() : providerCode);
        detail.setAccountNo(accountNo);
        detail.setUserAddress(confirmedAddress);
        detail.setAccountBalance(query.getAccountBalance());
        utilityDetailRepository.save(detail);

        paymentService.charge(userId, payMethod, amount, payPassword, order.getId(),
            "生活缴费-" + LifePaymentSupport.serviceTitle(serviceType) + " " + accountNo);
        Instant now = Instant.now();
        order.setPlatformPayStatus(PlatformPayStatus.paid);
        order.setOrderStatus(OrderStatus.paid);
        order.setPaidAt(now);
        orderRepository.save(order);

        query.setConfirmedAt(now);
        query.setQueryStatus(QueryStatus.confirmed);
        queryRepository.save(query);

        if (serviceType == ServiceType.electric && yuanrenFulfillmentService.shouldFulfill(ServiceType.electric)) {
            orderUpdateNoticeService.notify(order, "订单已支付，正在提交上游缴费");
            yuanrenFulfillmentService.scheduleSubmit(order.getOrderNo());
            logService.log(ActorType.user, userId, serviceType, order.getOrderNo(), null,
                "create_order", "电费订单已创建(大猿人)", sanitize(body), null);
            return toCreateOrderResponse(order, "订单已创建，等待上游缴费");
        }

        orderUpdateNoticeService.notify(order, "订单已创建，等待插件执行");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task_action", TaskAction.pay.name());
        payload.put("order_no", order.getOrderNo());
        payload.put("query_no", queryNo);
        payload.put("service_type", serviceType.name());
        payload.put("city_name", detail.getCityName());
        payload.put("city_code", LifePaymentSupport.nullToEmpty(detail.getCityCode()));
        payload.put("provider_name", detail.getProviderName());
        payload.put("provider_code", LifePaymentSupport.nullToEmpty(detail.getProviderCode()));
        payload.put("account_no", accountNo);
        payload.put("confirmed_user_address", confirmedAddress);
        payload.put("amount", amount);
        payload.put("payment_status", "paid");
        LifePaymentTask task = taskService.enqueue(order.getOrderNo(), queryNo, serviceType,
            TaskAction.pay, "paid", payload);

        logService.log(ActorType.user, userId, serviceType, order.getOrderNo(), task.getTaskNo(),
            "create_order", "水电燃气订单已创建", sanitize(body), null);

        Map<String, Object> resp = toCreateOrderResponse(order, "订单已创建，等待插件执行");
        resp.put("task_no", task.getTaskNo());
        return resp;
    }

    public Map<String, Object> getOrder(String userId, String orderNo) {
        catalogService.ensureEnabled();
        LifePaymentOrder order = orderRepository.findByUserIdAndOrderNo(userId, orderNo)
            .orElseThrow(() -> LifePaymentExceptions.notFound("order_not_found"));
        return toOrderDetail(order);
    }

    public Map<String, Object> listOrders(String userId, String serviceTypeRaw, String orderStatusRaw,
                                          int page, int pageSize) {
        catalogService.ensureEnabled();
        ServiceType serviceType = serviceTypeRaw == null || serviceTypeRaw.isBlank()
            ? null : ServiceType.require(serviceTypeRaw);
        OrderStatus orderStatus = null;
        if (orderStatusRaw != null && !orderStatusRaw.isBlank()) {
            try {
                orderStatus = OrderStatus.valueOf(orderStatusRaw.trim());
            } catch (IllegalArgumentException e) {
                throw LifePaymentExceptions.badRequest("INVALID_INPUT");
            }
        }
        int p = Math.max(page, 1);
        int size = Math.min(Math.max(pageSize, 1), 100);
        Page<LifePaymentOrder> result = orderRepository.search(userId, serviceType, orderStatus,
            PageRequest.of(p - 1, size));
        List<Map<String, Object>> items = result.getContent().stream().map(this::toOrderListItem).toList();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        resp.put("page", p);
        resp.put("page_size", size);
        resp.put("total", result.getTotalElements());
        return resp;
    }

    @Transactional
    public Map<String, Object> supplementOwnerLastChar(String userId, String orderNo, Map<String, Object> body) {
        catalogService.ensureEnabled();
        LifePaymentOrder order = orderRepository.findByUserIdAndOrderNo(userId, orderNo)
            .orElseThrow(() -> LifePaymentExceptions.notFound("order_not_found"));
        if (order.getServiceType() != ServiceType.mobile) {
            throw LifePaymentExceptions.badRequest("INVALID_INPUT");
        }
        if (order.getOrderStatus() != OrderStatus.need_owner_last_char
            && order.getPluginStatus() != PluginStatus.waiting_owner_last_char) {
            throw LifePaymentExceptions.conflict("INVALID_INPUT");
        }
        String ownerLastChar = requireText(body, "owner_last_char");
        if (ownerLastChar.length() > 4) {
            throw LifePaymentExceptions.badRequest("INVALID_INPUT");
        }

        LifePaymentMobileDetail detail = mobileDetailRepository.findByOrderNo(orderNo)
            .orElseThrow(() -> LifePaymentExceptions.notFound("order_not_found"));
        detail.setOwnerLastChar(ownerLastChar);
        mobileDetailRepository.save(detail);

        // 取消旧的 ready/running/need_manual 任务，重新排队
        List<LifePaymentTask> oldTasks = taskRepository.findByOrderNoAndStatusIn(orderNo,
            List.of(TaskStatus.ready, TaskStatus.running, TaskStatus.need_manual, TaskStatus.failed));
        for (LifePaymentTask old : oldTasks) {
            if (old.getStatus() != TaskStatus.success) {
                old.setStatus(TaskStatus.cancelled);
                old.setFinishedAt(Instant.now());
                taskRepository.save(old);
            }
        }

        order.setPluginStatus(PluginStatus.ready);
        order.setOrderStatus(OrderStatus.paid);
        orderRepository.save(order);

        if (yuanrenFulfillmentService.shouldFulfill(ServiceType.mobile)) {
            orderUpdateNoticeService.notify(order, "已补充机主信息，重新提交上游");
            yuanrenFulfillmentService.scheduleSubmit(order.getOrderNo());
            logService.log(ActorType.user, userId, ServiceType.mobile, orderNo, null,
                "supplement_owner_last_char", "已补充机主姓名最后一个字，重新提交大猿人",
                Map.of("owner_last_char", ownerLastChar), null);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("order_no", order.getOrderNo());
            resp.put("order_status", order.getOrderStatus().name());
            resp.put("plugin_status", order.getPluginStatus().name());
            resp.put("message", "已补充机主信息，重新提交上游");
            return resp;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task_action", TaskAction.recharge.name());
        payload.put("service_type", ServiceType.mobile.name());
        payload.put("order_no", order.getOrderNo());
        payload.put("account_no", detail.getPhone());
        payload.put("amount", order.getAmount());
        payload.put("payment_status", "paid");
        payload.put("owner_last_char", ownerLastChar);
        LifePaymentTask task = taskService.enqueue(order.getOrderNo(), null, ServiceType.mobile,
            TaskAction.recharge, "paid", payload);

        orderUpdateNoticeService.notify(order, "已补充机主姓名最后一个字，任务重新排队");

        logService.log(ActorType.user, userId, ServiceType.mobile, orderNo, task.getTaskNo(),
            "supplement_owner_last_char", "已补充机主姓名最后一个字，任务重新排队",
            Map.of("owner_last_char", ownerLastChar), null);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("order_no", order.getOrderNo());
        resp.put("order_status", order.getOrderStatus().name());
        resp.put("plugin_status", order.getPluginStatus().name());
        resp.put("task_no", task.getTaskNo());
        resp.put("message", "已补充机主姓名最后一个字，任务重新排队");
        return resp;
    }

    @Transactional
    public Map<String, Object> cancelOrder(String userId, String orderNo, Map<String, Object> body) {
        catalogService.ensureEnabled();
        LifePaymentOrder order = orderRepository.findByUserIdAndOrderNo(userId, orderNo)
            .orElseThrow(() -> LifePaymentExceptions.notFound("order_not_found"));
        if (order.getOrderStatus() == OrderStatus.cancelled) {
            return Map.of("order_no", orderNo, "order_status", "cancelled");
        }
        if (order.getOrderStatus() == OrderStatus.success
            || order.getPluginStatus() == PluginStatus.paid_success
            || order.getPluginStatus() == PluginStatus.cashier_confirm) {
            throw LifePaymentExceptions.conflict("order_cannot_cancel");
        }

        Instant now = Instant.now();
        List<LifePaymentTask> activeTasks = taskRepository.findByOrderNoAndStatusIn(orderNo,
            List.of(TaskStatus.ready, TaskStatus.running, TaskStatus.need_manual));
        for (LifePaymentTask task : activeTasks) {
            task.setStatus(TaskStatus.cancelled);
            task.setFinishedAt(now);
            task.setLockedBy(null);
            task.setLockedAt(null);
            taskRepository.save(task);
        }

        if (order.getPlatformPayStatus() == PlatformPayStatus.paid) {
            paymentService.refundIfNeeded(userId, order.getId(), "生活缴费取消退款 " + orderNo);
            order.setPlatformPayStatus(PlatformPayStatus.refunded);
        }
        order.setOrderStatus(OrderStatus.cancelled);
        order.setPluginStatus(PluginStatus.cancelled);
        orderRepository.save(order);
        orderUpdateNoticeService.notify(order, "订单已取消");

        String reason = asText(body == null ? null : body.get("reason"));
        logService.log(ActorType.user, userId, order.getServiceType(), orderNo, null,
            "cancel_order", reason == null || reason.isBlank() ? "用户取消" : reason, body, null);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("order_no", orderNo);
        resp.put("order_status", OrderStatus.cancelled.name());
        return resp;
    }

    private void validateMobileAmount(BigDecimal amount) {
        boolean ok = amountOptionRepository.findMobileOptions(ServiceType.mobile, null).stream()
            .anyMatch(o -> o.getAmount().compareTo(amount) == 0 && o.isEnabled());
        if (!ok) {
            throw LifePaymentExceptions.badRequest("invalid_amount");
        }
    }

    private void ensureProvider(ServiceType serviceType, String cityName, String cityCode,
                                String providerName, String providerCode) {
        providerRepository.findByProviderCode(providerCode).ifPresentOrElse(p -> {
            if (!p.isEnabled()) {
                throw LifePaymentExceptions.badRequest("provider_not_found");
            }
        }, () -> {
            LifePaymentProvider provider = new LifePaymentProvider();
            provider.setServiceType(serviceType);
            provider.setCityName(cityName);
            provider.setCityCode(cityCode);
            provider.setProviderName(providerName);
            provider.setProviderCode(providerCode);
            provider.setSource("user_submit");
            provider.setEnabled(true);
            provider.setLastCapturedAt(Instant.now());
            try {
                providerRepository.save(provider);
            } catch (DataIntegrityViolationException ignored) {
                // concurrent create
            }
        });
    }

    private void maybeExpireQuery(LifePaymentUtilityQuery query) {
        if (query.getQueryStatus() == QueryStatus.query_success
            && query.getExpiredAt() != null
            && query.getExpiredAt().isBefore(Instant.now())) {
            query.setQueryStatus(QueryStatus.expired);
            queryRepository.save(query);
        }
    }

    private Map<String, Object> toCreateOrderResponse(LifePaymentOrder order, String message) {
        String taskNo = taskRepository.findFirstByOrderNoAndStatusInOrderByCreatedAtDesc(
                order.getOrderNo(),
                List.of(TaskStatus.ready, TaskStatus.running, TaskStatus.success, TaskStatus.failed, TaskStatus.need_manual))
            .map(LifePaymentTask::getTaskNo)
            .orElse(null);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("order_no", order.getOrderNo());
        resp.put("service_type", order.getServiceType().name());
        resp.put("order_status", order.getOrderStatus().name());
        resp.put("platform_pay_status", order.getPlatformPayStatus().name());
        resp.put("plugin_status", order.getPluginStatus().name());
        resp.put("task_no", taskNo);
        resp.put("message", message);
        return resp;
    }

    private Map<String, Object> toRecentOrder(LifePaymentOrder order) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("order_no", order.getOrderNo());
        item.put("service_type", order.getServiceType().name());
        item.put("title", LifePaymentSupport.serviceTitle(order.getServiceType()));
        item.put("account_mask", resolveAccountMask(order));
        item.put("amount", LifePaymentSupport.formatAmount(order.getAmount()));
        item.put("order_status", order.getOrderStatus().name());
        item.put("created_at", LifePaymentSupport.formatTime(order.getCreatedAt()));
        return item;
    }

    private Map<String, Object> toOrderListItem(LifePaymentOrder order) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("order_no", order.getOrderNo());
        item.put("service_type", order.getServiceType().name());
        item.put("amount", order.getAmount());
        item.put("order_status", order.getOrderStatus().name());
        item.put("plugin_status", order.getPluginStatus().name());
        if (order.getServiceType() == ServiceType.mobile) {
            mobileDetailRepository.findByOrderNo(order.getOrderNo()).ifPresent(d ->
                item.put("account_no", d.getPhone()));
            item.put("provider_name", "");
        } else {
            utilityDetailRepository.findByOrderNo(order.getOrderNo()).ifPresent(d -> {
                item.put("account_no", d.getAccountNo());
                item.put("provider_name", d.getProviderName());
            });
        }
        item.put("created_at", LifePaymentSupport.formatTime(order.getCreatedAt()));
        return item;
    }

    Map<String, Object> toOrderDetail(LifePaymentOrder order) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("order_no", order.getOrderNo());
        resp.put("client_order_id", order.getClientOrderId());
        resp.put("service_type", order.getServiceType().name());
        resp.put("amount", order.getAmount());
        resp.put("pay_method", order.getPayMethod().name());
        resp.put("platform_pay_status", order.getPlatformPayStatus().name());
        resp.put("plugin_status", order.getPluginStatus().name());
        resp.put("order_status", order.getOrderStatus().name());
        resp.put("created_at", LifePaymentSupport.formatTime(order.getCreatedAt()));
        resp.put("updated_at", LifePaymentSupport.formatTime(order.getUpdatedAt()));
        if (order.getServiceType() == ServiceType.mobile) {
            mobileDetailRepository.findByOrderNo(order.getOrderNo()).ifPresent(d -> {
                resp.put("account_no", d.getPhone());
                resp.put("phone", d.getPhone());
                resp.put("owner_last_char", d.getOwnerLastChar());
                resp.put("recharge_status", d.getRechargeStatus());
                resp.put("receipt", d.getReceipt());
            });
        } else {
            utilityDetailRepository.findByOrderNo(order.getOrderNo()).ifPresent(d -> {
                resp.put("city_name", d.getCityName());
                resp.put("city_code", d.getCityCode());
                resp.put("provider_name", d.getProviderName());
                resp.put("provider_code", d.getProviderCode());
                resp.put("account_no", d.getAccountNo());
                resp.put("user_address", d.getUserAddress());
                resp.put("account_balance", d.getAccountBalance());
                resp.put("execution_status", d.getUtilityStatus());
                resp.put("receipt", d.getReceipt());
            });
        }
        return resp;
    }

    private Map<String, Object> toQueryResponse(LifePaymentUtilityQuery query) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("query_no", query.getQueryNo());
        resp.put("service_type", query.getServiceType().name());
        resp.put("city_name", query.getCityName());
        resp.put("city_code", query.getCityCode());
        resp.put("provider_name", query.getProviderName());
        resp.put("provider_code", query.getProviderCode());
        resp.put("account_no", query.getAccountNo());
        resp.put("user_address", query.getUserAddress());
        resp.put("account_balance", query.getAccountBalance());
        resp.put("suggest_amount", query.getSuggestAmount());
        resp.put("query_status", query.getQueryStatus().name());
        resp.put("plugin_status", query.getPluginStatus() == null ? null : query.getPluginStatus().name());
        resp.put("receipt", query.getReceipt());
        resp.put("expired_at", LifePaymentSupport.formatTime(query.getExpiredAt()));
        return resp;
    }

    private String resolveAccountMask(LifePaymentOrder order) {
        if (order.getServiceType() == ServiceType.mobile) {
            return mobileDetailRepository.findByOrderNo(order.getOrderNo())
                .map(d -> LifePaymentSupport.maskPhone(d.getPhone()))
                .orElse("");
        }
        return utilityDetailRepository.findByOrderNo(order.getOrderNo())
            .map(d -> LifePaymentSupport.maskAccount(d.getAccountNo()))
            .orElse("");
    }

    private static String requireText(Map<String, Object> body, String key) {
        String v = asText(body.get(key));
        if (v == null || v.isBlank()) {
            throw LifePaymentExceptions.badRequest("INVALID_INPUT");
        }
        return v.trim();
    }

    private static String asText(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private static BigDecimal asDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw LifePaymentExceptions.badRequest("invalid_amount");
        }
    }

    private static Map<String, Object> sanitize(Map<String, Object> body) {
        Map<String, Object> copy = new LinkedHashMap<>(body);
        if (copy.containsKey("pay_password")) {
            copy.put("pay_password", "******");
        }
        return copy;
    }
}
