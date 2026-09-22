package com.chat99.server.lifepayment;

import com.chat99.server.lifepayment.LifePaymentEnums.ActorType;
import com.chat99.server.lifepayment.LifePaymentEnums.OrderStatus;
import com.chat99.server.lifepayment.LifePaymentEnums.PluginStatus;
import com.chat99.server.lifepayment.LifePaymentEnums.QueryStatus;
import com.chat99.server.lifepayment.LifePaymentEnums.ServiceType;
import com.chat99.server.lifepayment.LifePaymentEnums.TaskAction;
import com.chat99.server.lifepayment.LifePaymentEnums.TaskStatus;
import com.chat99.server.lifepayment.LifePaymentEnums.WorkerStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LifePaymentTaskService {

    private static final Set<String> RETRYABLE = Set.of(
        "network_error", "page_unknown", "adb_error"
    );
    private static final Set<String> MANUAL = Set.of(
        "need_manual", "cashier_confirm"
    );
    private static final Set<String> TERMINAL_FAIL = Set.of(
        "provider_not_found", "account_not_found", "city_not_supported",
        "insufficient_balance", "payment_failed", "query_failed"
    );

    private final LifePaymentProperties properties;
    private final LifePaymentTaskRepository taskRepository;
    private final LifePaymentOrderRepository orderRepository;
    private final LifePaymentMobileDetailRepository mobileDetailRepository;
    private final LifePaymentUtilityDetailRepository utilityDetailRepository;
    private final LifePaymentUtilityQueryRepository queryRepository;
    private final LifePaymentWorkerDeviceRepository workerRepository;
    private final LifePaymentAccountService accountService;
    private final LifePaymentLogService logService;
    private final LifePaymentOrderUpdateNoticeService orderUpdateNoticeService;

    public LifePaymentTaskService(LifePaymentProperties properties,
                                  LifePaymentTaskRepository taskRepository,
                                  LifePaymentOrderRepository orderRepository,
                                  LifePaymentMobileDetailRepository mobileDetailRepository,
                                  LifePaymentUtilityDetailRepository utilityDetailRepository,
                                  LifePaymentUtilityQueryRepository queryRepository,
                                  LifePaymentWorkerDeviceRepository workerRepository,
                                  LifePaymentAccountService accountService,
                                  LifePaymentLogService logService,
                                  LifePaymentOrderUpdateNoticeService orderUpdateNoticeService) {
        this.properties = properties;
        this.taskRepository = taskRepository;
        this.orderRepository = orderRepository;
        this.mobileDetailRepository = mobileDetailRepository;
        this.utilityDetailRepository = utilityDetailRepository;
        this.queryRepository = queryRepository;
        this.workerRepository = workerRepository;
        this.accountService = accountService;
        this.logService = logService;
        this.orderUpdateNoticeService = orderUpdateNoticeService;
    }

    @Transactional
    public LifePaymentTask enqueue(String orderNo, String queryNo, ServiceType serviceType,
                                   TaskAction action, String paymentStatus, Map<String, Object> payload) {
        String accountNo = asText(payload.get("account_no"));
        String activeAccountKey = activeAccountKey(serviceType, accountNo);
        if (activeAccountKey != null && taskRepository.existsByActiveAccountKey(activeAccountKey)) {
            throw LifePaymentExceptions.conflict("account_task_already_active");
        }

        LifePaymentTask task = new LifePaymentTask();
        task.setTaskNo(LifePaymentSupport.newTaskNo(action == TaskAction.query ? "task-query" : "task"));
        task.setOrderNo(orderNo);
        task.setQueryNo(queryNo);
        task.setAccountNo(accountNo);
        task.setActiveAccountKey(activeAccountKey);
        task.setServiceType(serviceType);
        task.setTaskAction(action);
        task.setPaymentStatus(paymentStatus == null ? "none" : paymentStatus);
        task.setPayloadJson(LifePaymentSupport.toJson(payload));
        task.setStatus(TaskStatus.ready);
        task.setAttemptCount(0);
        task.setMaxAttempts(properties.getMaxAttempts());
        try {
            return taskRepository.saveAndFlush(task);
        } catch (DataIntegrityViolationException e) {
            if (activeAccountKey != null) {
                throw LifePaymentExceptions.conflict("account_task_already_active");
            }
            throw e;
        }
    }

    public void assertAccountAvailable(ServiceType serviceType, String accountNo) {
        String key = activeAccountKey(serviceType, accountNo);
        if (key != null && taskRepository.existsByActiveAccountKey(key)) {
            throw LifePaymentExceptions.conflict("account_task_already_active");
        }
    }

    @Transactional
    public Map<String, Object> claim(String workerId, List<String> supportTypesRaw) {
        if (workerId == null || workerId.isBlank()) {
            throw LifePaymentExceptions.badRequest("INVALID_INPUT");
        }
        List<ServiceType> requestedTypes = LifePaymentSupport.parseServiceTypes(supportTypesRaw);
        LifePaymentWorkerDevice worker = workerRepository.findByWorkerId(workerId).orElseGet(() -> {
            LifePaymentWorkerDevice created = new LifePaymentWorkerDevice();
            created.setWorkerId(workerId);
            created.setSupportServiceTypes(LifePaymentSupport.joinServiceTypes(requestedTypes));
            created.setStatus(WorkerStatus.online);
            created.setLastOnlineAt(Instant.now());
            return workerRepository.save(created);
        });
        if (worker.getStatus() == WorkerStatus.disabled) {
            throw LifePaymentExceptions.forbidden("FORBIDDEN");
        }
        if (taskRepository.existsByLockedByAndStatus(workerId, TaskStatus.running)) {
            Map<String, Object> busy = new LinkedHashMap<>();
            busy.put("task", null);
            return busy;
        }

        List<ServiceType> supportTypes = requestedTypes;
        List<ServiceType> workerTypes = LifePaymentSupport.parseServiceTypesCsv(worker.getSupportServiceTypes());
        if (!workerTypes.isEmpty()) {
            supportTypes = requestedTypes.stream().filter(workerTypes::contains).toList();
        }
        if (supportTypes.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("task", null);
            return empty;
        }

        Instant now = Instant.now();
        worker.setStatus(WorkerStatus.online);
        worker.setLastHeartbeatAt(now);
        workerRepository.save(worker);

        List<LifePaymentTask> candidates = taskRepository.findClaimCandidates(
            TaskStatus.ready, supportTypes, TaskAction.query, ServiceType.mobile, PageRequest.of(0, 20));
        for (LifePaymentTask candidate : candidates) {
            int updated = taskRepository.claimTask(candidate.getId(), workerId, now,
                TaskStatus.running, TaskStatus.ready);
            if (updated == 1) {
                LifePaymentTask claimed = taskRepository.findById(candidate.getId()).orElseThrow();
                worker.setStatus(WorkerStatus.busy);
                worker.setLastHeartbeatAt(now);
                workerRepository.save(worker);
                if (claimed.getOrderNo() != null) {
                    orderRepository.findByOrderNo(claimed.getOrderNo()).ifPresent(order -> {
                        order.setPluginStatus(PluginStatus.running);
                        order.setOrderStatus(OrderStatus.running);
                        orderRepository.save(order);
                        orderUpdateNoticeService.notify(order, "插件正在处理您的订单");
                    });
                }
                if (claimed.getQueryNo() != null && claimed.getTaskAction() == TaskAction.query) {
                    queryRepository.findByQueryNo(claimed.getQueryNo()).ifPresent(query -> {
                        query.setQueryStatus(QueryStatus.running);
                        query.setPluginStatus(PluginStatus.running);
                        queryRepository.save(query);
                    });
                }
                logService.log(ActorType.worker, workerId, claimed.getServiceType(),
                    claimed.getOrderNo(), claimed.getTaskNo(), "claim_task", "任务已领取", null, null);
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("task", buildTaskPayload(claimed));
                return resp;
            }
        }
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("task", null);
        return empty;
    }

    @Transactional
    public Map<String, Object> heartbeat(String taskNo, Map<String, Object> body) {
        String workerId = requireWorkerId(body);
        LifePaymentTask task = requireRunningTask(taskNo, workerId);
        Instant now = Instant.now();
        task.setHeartbeatAt(now);
        taskRepository.save(task);
        workerRepository.findByWorkerId(workerId).ifPresent(w -> {
            w.setLastHeartbeatAt(now);
            w.setStatus(WorkerStatus.busy);
            workerRepository.save(w);
        });
        String step = asText(body.get("step"));
        String message = asText(body.get("message"));
        logService.log(ActorType.worker, workerId, task.getServiceType(), task.getOrderNo(), taskNo,
            "plugin_step", message == null ? step : message, body, null);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("task_no", taskNo);
        resp.put("status", task.getStatus().name());
        resp.put("server_time", LifePaymentSupport.formatTime(now));
        return resp;
    }

    @Transactional
    public Map<String, Object> complete(String taskNo, Map<String, Object> body) {
        String workerId = requireWorkerId(body);
        LifePaymentTask task = taskRepository.findByTaskNo(taskNo)
            .orElseThrow(() -> LifePaymentExceptions.notFound("order_not_found"));
        if (task.getStatus() == TaskStatus.success) {
            return completeResponse(task, "任务已完成");
        }
        if (task.getStatus() != TaskStatus.running) {
            throw LifePaymentExceptions.conflict("INVALID_INPUT");
        }
        if (!workerId.equals(task.getLockedBy())) {
            throw LifePaymentExceptions.forbidden("FORBIDDEN");
        }

        PluginStatus pluginStatus = parsePluginStatus(asText(body.get("plugin_status")), PluginStatus.paid_success);
        Instant now = Instant.now();

        if (task.getTaskAction() == TaskAction.query) {
            handleQueryComplete(task, body, pluginStatus, now);
            task.setStatus(TaskStatus.success);
            task.setFinishedAt(now);
            markWorkerOnline(workerId, now);
        } else if (pluginStatus == PluginStatus.cashier_confirm || pluginStatus == PluginStatus.processing) {
            handlePayComplete(task, body, pluginStatus, now);
            task.setHeartbeatAt(now);
            // 仍在支付确认/处理中，任务保持 running，等待最终结果回调
        } else {
            handlePayComplete(task, body, pluginStatus, now);
            task.setStatus(TaskStatus.success);
            task.setFinishedAt(now);
            markWorkerOnline(workerId, now);
        }
        task.setHeartbeatAt(now);
        taskRepository.save(task);

        logService.log(ActorType.worker, workerId, task.getServiceType(), task.getOrderNo(), taskNo,
            "plugin_complete", "任务完成", body, null);
        return completeResponse(task, "任务已完成");
    }

    @Transactional
    public Map<String, Object> fail(String taskNo, Map<String, Object> body) {
        String workerId = requireWorkerId(body);
        LifePaymentTask task = taskRepository.findByTaskNo(taskNo)
            .orElseThrow(() -> LifePaymentExceptions.notFound("order_not_found"));
        if (task.getStatus() == TaskStatus.success) {
            logService.log(ActorType.worker, workerId, task.getServiceType(), task.getOrderNo(), taskNo,
                "plugin_fail_ignored", "成功任务忽略失败回调", body, null);
            return failResponse(task, "失败状态已记录");
        }
        if (task.getStatus() == TaskStatus.failed || task.getStatus() == TaskStatus.need_manual) {
            return failResponse(task, "失败状态已记录");
        }
        if (task.getStatus() != TaskStatus.running) {
            throw LifePaymentExceptions.conflict("INVALID_INPUT");
        }
        if (!workerId.equals(task.getLockedBy())) {
            throw LifePaymentExceptions.forbidden("FORBIDDEN");
        }

        String errorCode = asText(body.get("error_code"));
        if (errorCode == null || errorCode.isBlank()) {
            errorCode = asText(body.get("plugin_status"));
        }
        if (errorCode == null || errorCode.isBlank()) {
            errorCode = "failed";
        }
        String errorMessage = asText(body.get("error_message"));
        PluginStatus pluginStatus = parsePluginStatus(asText(body.get("plugin_status")), PluginStatus.failed);
        Instant now = Instant.now();

        task.setLastErrorCode(errorCode);
        task.setLastErrorMessage(errorMessage);
        task.setHeartbeatAt(now);

        if (task.getTaskAction() == TaskAction.query) {
            queryRepository.findByQueryNo(task.getQueryNo()).ifPresent(query -> {
                query.setQueryStatus(QueryStatus.query_failed);
                query.setPluginStatus(pluginStatus == PluginStatus.ready ? PluginStatus.query_failed : pluginStatus);
                query.setReceipt(asText(body.get("receipt")));
                queryRepository.save(query);
            });
            task.setStatus(TaskStatus.failed);
            task.setFinishedAt(now);
        } else if ("waiting_owner_last_char".equals(errorCode) || pluginStatus == PluginStatus.waiting_owner_last_char) {
            task.setStatus(TaskStatus.need_manual);
            task.setFinishedAt(now);
            updateOrderOnFail(task, OrderStatus.need_owner_last_char, PluginStatus.waiting_owner_last_char, body);
        } else if (MANUAL.contains(errorCode) || pluginStatus == PluginStatus.need_manual) {
            task.setStatus(TaskStatus.need_manual);
            task.setFinishedAt(now);
            updateOrderOnFail(task, OrderStatus.need_manual, PluginStatus.need_manual, body);
        } else if (RETRYABLE.contains(errorCode) && task.getAttemptCount() < task.getMaxAttempts()) {
            task.setStatus(TaskStatus.ready);
            task.setLockedBy(null);
            task.setLockedAt(null);
            task.setHeartbeatAt(null);
            if (task.getOrderNo() != null) {
                orderRepository.findByOrderNo(task.getOrderNo()).ifPresent(order -> {
                    if (order.getOrderStatus() != OrderStatus.success) {
                        order.setPluginStatus(PluginStatus.ready);
                        order.setOrderStatus(OrderStatus.paid);
                        orderRepository.save(order);
                    }
                });
            }
        } else if (TERMINAL_FAIL.contains(errorCode) || task.getAttemptCount() >= task.getMaxAttempts()) {
            TaskStatus finalStatus = task.getAttemptCount() >= task.getMaxAttempts()
                ? TaskStatus.need_manual : TaskStatus.failed;
            OrderStatus orderStatus = finalStatus == TaskStatus.need_manual
                ? OrderStatus.need_manual : OrderStatus.failed;
            PluginStatus finalPlugin = finalStatus == TaskStatus.need_manual
                ? PluginStatus.need_manual : pluginStatus;
            task.setStatus(finalStatus);
            task.setFinishedAt(now);
            updateOrderOnFail(task, orderStatus, finalPlugin, body);
        } else {
            task.setStatus(TaskStatus.failed);
            task.setFinishedAt(now);
            updateOrderOnFail(task, OrderStatus.failed, pluginStatus, body);
        }

        taskRepository.save(task);
        markWorkerOnline(workerId, now);
        logService.log(ActorType.worker, workerId, task.getServiceType(), task.getOrderNo(), taskNo,
            "plugin_fail", errorMessage == null ? errorCode : errorMessage, body, null);
        return failResponse(task, "失败状态已记录");
    }

    @Transactional
    public void recoverTimedOut() {
        Instant now = Instant.now();
        Instant deadline = now.minus(properties.getTaskHeartbeatTimeoutSeconds(), ChronoUnit.SECONDS);
        taskRepository.recoverTimedOutTasks(deadline, now, TaskStatus.ready, TaskStatus.running);
        taskRepository.markTimedOutNeedManual(deadline, now, TaskStatus.need_manual, TaskStatus.running);
        List<LifePaymentTask> needManual = taskRepository.findByStatusAndHeartbeatAtBefore(TaskStatus.need_manual, deadline);
        for (LifePaymentTask task : needManual) {
            if (task.getOrderNo() != null) {
                orderRepository.findByOrderNo(task.getOrderNo()).ifPresent(order -> {
                    if (order.getOrderStatus() != OrderStatus.success
                        && order.getOrderStatus() != OrderStatus.cancelled) {
                        order.setOrderStatus(OrderStatus.need_manual);
                        order.setPluginStatus(PluginStatus.need_manual);
                        orderRepository.save(order);
                        orderUpdateNoticeService.notify(order, "订单心跳超时，需人工处理");
                    }
                });
            }
        }
    }

    private void handleQueryComplete(LifePaymentTask task, Map<String, Object> body,
                                     PluginStatus pluginStatus, Instant now) {
        LifePaymentUtilityQuery query = queryRepository.findByQueryNo(task.getQueryNo())
            .orElseThrow(() -> LifePaymentExceptions.notFound("order_not_found"));
        query.setUserAddress(asText(body.get("user_address")));
        query.setAccountBalance(asText(body.get("account_balance")));
        query.setSuggestAmount(asText(body.get("suggest_amount")));
        query.setReceipt(asText(body.get("receipt")));
        if (body.get("provider_name") != null && !asText(body.get("provider_name")).isBlank()) {
            query.setProviderName(asText(body.get("provider_name")));
        }
        if (body.get("city_name") != null && !asText(body.get("city_name")).isBlank()) {
            query.setCityName(asText(body.get("city_name")));
        }
        query.setQueryStatus(QueryStatus.query_success);
        query.setPluginStatus(pluginStatus == PluginStatus.paid_success ? PluginStatus.query_success : pluginStatus);
        if (query.getPluginStatus() == null || query.getPluginStatus() == PluginStatus.ready) {
            query.setPluginStatus(PluginStatus.query_success);
        }
        query.setExpiredAt(now.plus(properties.getQueryExpireMinutes(), ChronoUnit.MINUTES));
        queryRepository.save(query);
    }

    private void handlePayComplete(LifePaymentTask task, Map<String, Object> body,
                                   PluginStatus pluginStatus, Instant now) {
        LifePaymentOrder order = orderRepository.findByOrderNo(task.getOrderNo())
            .orElseThrow(() -> LifePaymentExceptions.notFound("order_not_found"));
        if (order.getOrderStatus() == OrderStatus.success) {
            return;
        }

        if (pluginStatus == PluginStatus.cashier_confirm) {
            order.setPluginStatus(PluginStatus.cashier_confirm);
            order.setOrderStatus(OrderStatus.cashier_confirm);
            orderRepository.save(order);
            orderUpdateNoticeService.notify(order, "已到支付确认环节");
            updateDetailOnCallback(order, body, "已到支付阶段");
            return;
        }
        if (pluginStatus == PluginStatus.processing) {
            order.setPluginStatus(PluginStatus.processing);
            order.setOrderStatus(OrderStatus.processing);
            orderRepository.save(order);
            orderUpdateNoticeService.notify(order, "缴费处理中，请稍候");
            updateDetailOnCallback(order, body, asText(body.get("execution_status")));
            return;
        }

        order.setPluginStatus(PluginStatus.paid_success);
        order.setOrderStatus(OrderStatus.success);
        orderRepository.save(order);
        orderUpdateNoticeService.notify(order,
            order.getServiceType() == ServiceType.mobile ? "充值成功" : "缴费成功");
        updateDetailOnCallback(order, body,
            order.getServiceType() == ServiceType.mobile
                ? defaultText(asText(body.get("recharge_status")), "充值成功")
                : defaultText(asText(body.get("execution_status")), "缴费成功"));

        if (order.getServiceType() == ServiceType.mobile) {
            mobileDetailRepository.findByOrderNo(order.getOrderNo()).ifPresent(d ->
                accountService.markSuccess(ServiceType.mobile, d.getPhone(), null, "", null, "",
                    d.getOwnerLastChar(), null, order.getOrderNo()));
        } else {
            utilityDetailRepository.findByOrderNo(order.getOrderNo()).ifPresent(d ->
                accountService.markSuccess(order.getServiceType(), d.getAccountNo(), d.getCityName(),
                    LifePaymentSupport.blankToEmpty(d.getCityCode()), d.getProviderName(),
                    LifePaymentSupport.blankToEmpty(d.getProviderCode()), null, d.getUserAddress(),
                    order.getOrderNo()));
        }
    }

    private void updateDetailOnCallback(LifePaymentOrder order, Map<String, Object> body, String statusText) {
        if (order.getServiceType() == ServiceType.mobile) {
            mobileDetailRepository.findByOrderNo(order.getOrderNo()).ifPresent(d -> {
                d.setRechargeStatus(defaultText(asText(body.get("recharge_status")), statusText));
                d.setReceipt(asText(body.get("receipt")));
                d.setAlipayTradeNo(asText(body.get("alipay_trade_no")));
                BigDecimal paid = asDecimal(body.get("paid_amount"));
                if (paid != null) {
                    d.setPaidAmount(paid);
                }
                mobileDetailRepository.save(d);
            });
        } else {
            utilityDetailRepository.findByOrderNo(order.getOrderNo()).ifPresent(d -> {
                d.setUtilityStatus(defaultText(asText(body.get("execution_status")), statusText));
                d.setReceipt(asText(body.get("receipt")));
                if (body.get("user_address") != null) {
                    d.setUserAddress(asText(body.get("user_address")));
                }
                if (body.get("account_balance") != null) {
                    d.setAccountBalance(asText(body.get("account_balance")));
                }
                d.setAlipayTradeNo(asText(body.get("alipay_trade_no")));
                BigDecimal paid = asDecimal(body.get("paid_amount"));
                if (paid != null) {
                    d.setPaidAmount(paid);
                }
                utilityDetailRepository.save(d);
            });
        }
    }

    private void updateOrderOnFail(LifePaymentTask task, OrderStatus orderStatus,
                                   PluginStatus pluginStatus, Map<String, Object> body) {
        if (task.getOrderNo() == null) {
            return;
        }
        orderRepository.findByOrderNo(task.getOrderNo()).ifPresent(order -> {
            if (order.getOrderStatus() == OrderStatus.success) {
                return;
            }
            order.setOrderStatus(orderStatus);
            order.setPluginStatus(pluginStatus);
            orderRepository.save(order);
            orderUpdateNoticeService.notify(order, asText(body.get("error_message")));
            updateDetailOnCallback(order, body,
                defaultText(asText(body.get("receipt")), asText(body.get("error_message"))));
        });
    }

    private Map<String, Object> buildTaskPayload(LifePaymentTask task) {
        Map<String, Object> payload = LifePaymentSupport.parseJsonMap(task.getPayloadJson());
        payload.put("task_no", task.getTaskNo());
        if (task.getOrderNo() != null) {
            payload.put("order_no", task.getOrderNo());
        }
        if (task.getQueryNo() != null) {
            payload.put("query_no", task.getQueryNo());
        }
        payload.put("service_type", task.getServiceType().name());
        payload.put("task_action", task.getTaskAction().name());
        if (task.getTaskAction() != TaskAction.query) {
            payload.put("payment_status", task.getPaymentStatus());
        }
        return payload;
    }

    private Map<String, Object> completeResponse(LifePaymentTask task, String message) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("task_no", task.getTaskNo());
        resp.put("order_no", task.getOrderNo());
        if (task.getOrderNo() != null) {
            orderRepository.findByOrderNo(task.getOrderNo()).ifPresent(o ->
                resp.put("order_status", o.getOrderStatus().name()));
        } else {
            resp.put("order_status", null);
        }
        resp.put("message", message);
        return resp;
    }

    private Map<String, Object> failResponse(LifePaymentTask task, String message) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("task_no", task.getTaskNo());
        resp.put("order_no", task.getOrderNo());
        if (task.getOrderNo() != null) {
            orderRepository.findByOrderNo(task.getOrderNo()).ifPresent(o ->
                resp.put("order_status", o.getOrderStatus().name()));
        } else {
            resp.put("order_status", null);
        }
        resp.put("message", message);
        return resp;
    }

    private LifePaymentTask requireRunningTask(String taskNo, String workerId) {
        LifePaymentTask task = taskRepository.findByTaskNo(taskNo)
            .orElseThrow(() -> LifePaymentExceptions.notFound("order_not_found"));
        if (task.getStatus() != TaskStatus.running) {
            throw LifePaymentExceptions.conflict("INVALID_INPUT");
        }
        if (!workerId.equals(task.getLockedBy())) {
            throw LifePaymentExceptions.forbidden("FORBIDDEN");
        }
        return task;
    }

    private void markWorkerOnline(String workerId, Instant now) {
        workerRepository.findByWorkerId(workerId).ifPresent(w -> {
            w.setStatus(WorkerStatus.online);
            w.setLastHeartbeatAt(now);
            workerRepository.save(w);
        });
    }

    private static String requireWorkerId(Map<String, Object> body) {
        String workerId = asText(body.get("worker_id"));
        if (workerId == null || workerId.isBlank()) {
            throw LifePaymentExceptions.badRequest("INVALID_INPUT");
        }
        return workerId;
    }

    private static PluginStatus parsePluginStatus(String raw, PluginStatus fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return PluginStatus.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static String asText(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
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
        } catch (Exception e) {
            return null;
        }
    }

    private static String activeAccountKey(ServiceType serviceType, String accountNo) {
        if (serviceType == null || !serviceType.isUtility()) {
            return null;
        }
        if (accountNo == null || accountNo.isBlank()) {
            throw LifePaymentExceptions.badRequest("account_no_required");
        }
        return serviceType.name() + ":" + accountNo.trim();
    }
}
