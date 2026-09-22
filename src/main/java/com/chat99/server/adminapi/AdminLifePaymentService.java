package com.chat99.server.adminapi;

import com.chat99.server.lifepayment.LifePaymentEnums.ActorType;
import com.chat99.server.lifepayment.LifePaymentEnums.OrderStatus;
import com.chat99.server.lifepayment.LifePaymentEnums.PluginStatus;
import com.chat99.server.lifepayment.LifePaymentEnums.ServiceType;
import com.chat99.server.lifepayment.LifePaymentEnums.TaskStatus;
import com.chat99.server.lifepayment.LifePaymentEnums.WorkerStatus;
import com.chat99.server.lifepayment.LifePaymentLogService;
import com.chat99.server.lifepayment.LifePaymentMobileDetail;
import com.chat99.server.lifepayment.LifePaymentMobileDetailRepository;
import com.chat99.server.lifepayment.LifePaymentOperationLog;
import com.chat99.server.lifepayment.LifePaymentOperationLogRepository;
import com.chat99.server.lifepayment.LifePaymentOrder;
import com.chat99.server.lifepayment.LifePaymentOrderRepository;
import com.chat99.server.lifepayment.LifePaymentProvider;
import com.chat99.server.lifepayment.LifePaymentProviderRepository;
import com.chat99.server.lifepayment.LifePaymentSupport;
import com.chat99.server.lifepayment.LifePaymentTask;
import com.chat99.server.lifepayment.LifePaymentTaskRepository;
import com.chat99.server.lifepayment.LifePaymentUtilityDetail;
import com.chat99.server.lifepayment.LifePaymentUtilityDetailRepository;
import com.chat99.server.lifepayment.LifePaymentWorkerDevice;
import com.chat99.server.lifepayment.LifePaymentWorkerDeviceRepository;
import com.chat99.server.lifepayment.LifePaymentOrderUpdateNoticeService;
import com.chat99.server.lifepayment.LifePaymentPaymentService;
import com.chat99.server.lifepayment.LifePaymentEnums.PlatformPayStatus;
import com.chat99.server.lifepayment.LifePaymentWorkerTokenSupport;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminLifePaymentService {

    private final LifePaymentOrderRepository orderRepository;
    private final LifePaymentTaskRepository taskRepository;
    private final LifePaymentMobileDetailRepository mobileDetailRepository;
    private final LifePaymentUtilityDetailRepository utilityDetailRepository;
    private final LifePaymentOperationLogRepository logRepository;
    private final LifePaymentWorkerDeviceRepository workerRepository;
    private final LifePaymentProviderRepository providerRepository;
    private final LifePaymentLogService lifePaymentLogService;
    private final LifePaymentPaymentService paymentService;
    private final AdminAuditService auditService;
    private final LifePaymentOrderUpdateNoticeService orderUpdateNoticeService;

    public AdminLifePaymentService(LifePaymentOrderRepository orderRepository,
                                   LifePaymentTaskRepository taskRepository,
                                   LifePaymentMobileDetailRepository mobileDetailRepository,
                                   LifePaymentUtilityDetailRepository utilityDetailRepository,
                                   LifePaymentOperationLogRepository logRepository,
                                   LifePaymentWorkerDeviceRepository workerRepository,
                                   LifePaymentProviderRepository providerRepository,
                                   LifePaymentLogService lifePaymentLogService,
                                   LifePaymentPaymentService paymentService,
                                   AdminAuditService auditService,
                                   LifePaymentOrderUpdateNoticeService orderUpdateNoticeService) {
        this.orderRepository = orderRepository;
        this.taskRepository = taskRepository;
        this.mobileDetailRepository = mobileDetailRepository;
        this.utilityDetailRepository = utilityDetailRepository;
        this.logRepository = logRepository;
        this.workerRepository = workerRepository;
        this.providerRepository = providerRepository;
        this.lifePaymentLogService = lifePaymentLogService;
        this.paymentService = paymentService;
        this.auditService = auditService;
        this.orderUpdateNoticeService = orderUpdateNoticeService;
    }

    public PageResponse<OrderItem> listOrders(String userId, String orderNo, String serviceTypeRaw,
                                              String orderStatusRaw, int page, int pageSize) {
        ServiceType serviceType = parseServiceTypeOrNull(serviceTypeRaw);
        OrderStatus orderStatus = parseOrderStatusOrNull(orderStatusRaw);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        Page<LifePaymentOrder> result = orderRepository.adminSearch(
            blankToNull(userId), blankToNull(orderNo), serviceType, orderStatus,
            PageRequest.of(safePage - 1, safeSize));
        List<OrderItem> items = result.getContent().stream().map(this::toOrderItem).toList();
        return new PageResponse<>(items, result.getTotalElements(), safePage, safeSize, result.hasNext());
    }

    public OrderItem getOrder(String orderNo) {
        LifePaymentOrder order = orderRepository.findByOrderNo(orderNo)
            .orElseThrow(() -> new AdminApiException(HttpStatus.NOT_FOUND, "not_found", "order_not_found"));
        return toOrderItem(order);
    }

    public PageResponse<TaskItem> listTasks(String taskNo, String orderNo, String serviceTypeRaw,
                                            String statusRaw, int page, int pageSize) {
        ServiceType serviceType = parseServiceTypeOrNull(serviceTypeRaw);
        TaskStatus status = parseTaskStatusOrNull(statusRaw);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        Page<LifePaymentTask> result = taskRepository.adminSearch(
            blankToNull(taskNo), blankToNull(orderNo), serviceType, status,
            PageRequest.of(safePage - 1, safeSize));
        List<TaskItem> items = result.getContent().stream().map(this::toTaskItem).toList();
        return new PageResponse<>(items, result.getTotalElements(), safePage, safeSize, result.hasNext());
    }

    public TaskItem getTask(String taskNo) {
        LifePaymentTask task = taskRepository.findByTaskNo(taskNo)
            .orElseThrow(() -> new AdminApiException(HttpStatus.NOT_FOUND, "not_found", "task_not_found"));
        return toTaskItem(task);
    }

    @Transactional
    public TaskItem retryTask(HttpServletRequest http, Authentication auth, String taskNo, ReasonRequest body) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        LifePaymentTask task = taskRepository.findByTaskNo(taskNo)
            .orElseThrow(() -> new AdminApiException(HttpStatus.NOT_FOUND, "not_found", "task_not_found"));
        if (task.getStatus() == TaskStatus.success) {
            throw new AdminApiException(HttpStatus.CONFLICT, "conflict", "task already success");
        }
        if (task.getStatus() == TaskStatus.running) {
            throw new AdminApiException(HttpStatus.CONFLICT, "conflict", "task is running");
        }
        if (task.getStatus() == TaskStatus.cancelled) {
            throw new AdminApiException(HttpStatus.CONFLICT, "conflict", "task cancelled");
        }

        Instant now = Instant.now();
        task.setStatus(TaskStatus.ready);
        task.setAttemptCount(0);
        task.setLockedBy(null);
        task.setLockedAt(null);
        task.setHeartbeatAt(null);
        task.setFinishedAt(null);
        task.setLastErrorCode(null);
        task.setLastErrorMessage(null);
        task.setUpdatedAt(now);
        taskRepository.save(task);

        if (task.getOrderNo() != null) {
            orderRepository.findByOrderNo(task.getOrderNo()).ifPresent(order -> {
                if (order.getOrderStatus() != OrderStatus.success
                    && order.getOrderStatus() != OrderStatus.cancelled) {
                    order.setPluginStatus(PluginStatus.ready);
                    order.setOrderStatus(OrderStatus.paid);
                    orderRepository.save(order);
                    orderUpdateNoticeService.notify(order, "任务已重新排队");
                }
            });
        }

        String reason = body == null || body.reason() == null || body.reason().isBlank()
            ? "admin retry" : body.reason().trim();
        lifePaymentLogService.log(ActorType.admin, admin.username(), task.getServiceType(),
            task.getOrderNo(), task.getTaskNo(), "retry_task", reason, body, null);
        auditService.log(http, admin.username(), "life_payment.task.retry", null,
            Map.of("taskNo", taskNo, "reason", reason));
        return toTaskItem(task);
    }

    @Transactional
    public OrderItem markManual(HttpServletRequest http, Authentication auth, String orderNo, ReasonRequest body) {
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

        Instant now = Instant.now();
        order.setOrderStatus(OrderStatus.need_manual);
        order.setPluginStatus(PluginStatus.need_manual);
        orderRepository.save(order);
        orderUpdateNoticeService.notify(order, "订单已标记为需人工处理");

        List<LifePaymentTask> tasks = taskRepository.findByOrderNoAndStatusIn(orderNo,
            List.of(TaskStatus.ready, TaskStatus.running, TaskStatus.failed));
        for (LifePaymentTask task : tasks) {
            task.setStatus(TaskStatus.need_manual);
            task.setFinishedAt(now);
            task.setLockedBy(null);
            task.setLockedAt(null);
            taskRepository.save(task);
        }

        String reason = body == null || body.reason() == null || body.reason().isBlank()
            ? "admin mark manual" : body.reason().trim();
        lifePaymentLogService.log(ActorType.admin, admin.username(), order.getServiceType(),
            orderNo, null, "mark_manual", reason, body, null);
        auditService.log(http, admin.username(), "life_payment.order.manual", order.getUserId(),
            Map.of("orderNo", orderNo, "reason", reason));
        return toOrderItem(order);
    }

    @Transactional
    public OrderItem markFailedAndRefund(HttpServletRequest http, Authentication auth, String orderNo,
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

    public LogListResponse listOrderLogs(String orderNo) {
        if (orderRepository.findByOrderNo(orderNo).isEmpty()) {
            throw new AdminApiException(HttpStatus.NOT_FOUND, "not_found", "order_not_found");
        }
        List<LogItem> items = logRepository.findByOrderNoOrderByCreatedAtAsc(orderNo).stream()
            .map(this::toLogItem)
            .toList();
        return new LogListResponse(items);
    }

    public WorkerListResponse listWorkers(String statusRaw) {
        List<LifePaymentWorkerDevice> workers;
        if (statusRaw != null && !statusRaw.isBlank()) {
            WorkerStatus status;
            try {
                status = WorkerStatus.valueOf(statusRaw.trim());
            } catch (IllegalArgumentException e) {
                throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "invalid status");
            }
            workers = workerRepository.findByStatusOrderByUpdatedAtDesc(status);
        } else {
            workers = workerRepository.findAllByOrderByUpdatedAtDesc();
        }
        return new WorkerListResponse(workers.stream().map(this::toWorkerItem).toList());
    }

    @Transactional
    public IssueWorkerTokenResponse issueWorkerToken(HttpServletRequest http, Authentication auth,
                                                       String workerId, IssueWorkerTokenRequest body) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        if (workerId == null || workerId.isBlank()) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "worker_id required");
        }
        String normalizedWorkerId = workerId.trim();
        LifePaymentWorkerDevice worker = workerRepository.findByWorkerId(normalizedWorkerId).orElseGet(() -> {
            LifePaymentWorkerDevice created = new LifePaymentWorkerDevice();
            created.setWorkerId(normalizedWorkerId);
            created.setSupportServiceTypes("");
            created.setStatus(WorkerStatus.offline);
            return created;
        });
        if (body != null) {
            if (body.deviceId() != null && !body.deviceId().isBlank()) {
                worker.setDeviceId(body.deviceId().trim());
            }
            if (body.deviceName() != null && !body.deviceName().isBlank()) {
                worker.setDeviceName(body.deviceName().trim());
            }
            if (body.supportServiceTypes() != null && !body.supportServiceTypes().isEmpty()) {
                List<ServiceType> types = LifePaymentSupport.parseServiceTypes(body.supportServiceTypes());
                worker.setSupportServiceTypes(LifePaymentSupport.joinServiceTypes(types));
            }
            if (body.remark() != null) {
                worker.setRemark(body.remark().trim());
            }
        }
        String plainToken = LifePaymentWorkerTokenSupport.generateToken();
        worker.setWorkerTokenHash(LifePaymentWorkerTokenSupport.hashToken(plainToken));
        if (worker.getSupportServiceTypes() == null || worker.getSupportServiceTypes().isBlank()) {
            worker.setSupportServiceTypes("mobile,water,electric,gas");
        }
        workerRepository.save(worker);
        auditService.log(http, admin.username(), "life_payment.worker.issue_token", null,
            Map.of("workerId", normalizedWorkerId));
        return new IssueWorkerTokenResponse(
            worker.getWorkerId(),
            plainToken,
            worker.getDeviceName(),
            LifePaymentSupport.parseServiceTypesCsv(worker.getSupportServiceTypes()).stream()
                .map(Enum::name).toList(),
            true
        );
    }

    public PageResponse<ProviderItem> listProviders(String serviceTypeRaw, String cityName, String cityCode,
                                                    String keyword, Boolean enabled, int page, int pageSize) {
        ServiceType serviceType = parseServiceTypeOrNull(serviceTypeRaw);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        Page<LifePaymentProvider> result = providerRepository.adminSearch(
            serviceType, blankToNull(cityCode), blankToNull(cityName), blankToNull(keyword), enabled,
            PageRequest.of(safePage - 1, safeSize));
        List<ProviderItem> items = result.getContent().stream().map(this::toProviderItem).toList();
        return new PageResponse<>(items, result.getTotalElements(), safePage, safeSize, result.hasNext());
    }

    @Transactional
    public ProviderEnabledResponse setProviderEnabled(HttpServletRequest http, Authentication auth,
                                                      String providerCode, EnabledRequest body) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        if (body == null || body.enabled() == null) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "enabled required");
        }
        LifePaymentProvider provider = providerRepository.findByProviderCode(providerCode)
            .orElseThrow(() -> new AdminApiException(HttpStatus.NOT_FOUND, "not_found", "provider_not_found"));
        provider.setEnabled(body.enabled());
        providerRepository.save(provider);
        auditService.log(http, admin.username(), "life_payment.provider.enabled", null,
            Map.of("providerCode", providerCode, "enabled", body.enabled()));
        return new ProviderEnabledResponse(provider.getProviderCode(), provider.isEnabled());
    }

    @Transactional
    public ImportProvidersResponse importProviders(HttpServletRequest http, Authentication auth,
                                                   ImportProvidersRequest body) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        if (body == null || body.serviceType() == null || body.serviceType().isBlank()) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "service_type required");
        }
        if (body.items() == null || body.items().isEmpty()) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "items required");
        }
        ServiceType serviceType;
        try {
            serviceType = ServiceType.requireUtility(body.serviceType());
        } catch (Exception e) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "invalid service_type");
        }
        String source = body.source() == null || body.source().isBlank() ? "alipay_capture" : body.source().trim();
        int inserted = 0;
        int updated = 0;
        int skipped = 0;
        Instant now = Instant.now();
        for (ImportProviderItem item : body.items()) {
            if (item == null || item.providerName() == null || item.providerName().isBlank()
                || item.cityName() == null || item.cityName().isBlank()) {
                skipped++;
                continue;
            }
            String cityName = item.cityName().trim();
            String cityCode = item.cityCode() == null ? "" : item.cityCode().trim();
            String providerName = item.providerName().trim();
            LifePaymentProvider existing = null;
            if (!cityCode.isBlank()) {
                existing = providerRepository
                    .findByServiceTypeAndCityCodeAndProviderName(serviceType, cityCode, providerName)
                    .orElse(null);
            }
            if (existing == null) {
                existing = providerRepository
                    .findByServiceTypeAndCityNameAndProviderName(serviceType, cityName, providerName)
                    .orElse(null);
            }
            if (existing != null) {
                boolean changed = false;
                if (!cityCode.isBlank() && !cityCode.equals(LifePaymentSupport.blankToEmpty(existing.getCityCode()))) {
                    existing.setCityCode(cityCode);
                    changed = true;
                }
                if (!cityName.equals(existing.getCityName())) {
                    existing.setCityName(cityName);
                    changed = true;
                }
                if (item.provinceName() != null && !item.provinceName().isBlank()) {
                    existing.setProvinceName(item.provinceName().trim());
                    changed = true;
                }
                existing.setSource(source);
                existing.setLastCapturedAt(now);
                existing.setEnabled(true);
                providerRepository.save(existing);
                if (changed) {
                    updated++;
                } else {
                    updated++;
                }
            } else {
                String providerCode = item.providerCode() == null || item.providerCode().isBlank()
                    ? LifePaymentSupport.providerCodeOf(serviceType, cityCode, cityName, providerName)
                    : item.providerCode().trim();
                if (providerRepository.findByProviderCode(providerCode).isPresent()) {
                    providerCode = providerCode + "_" + System.currentTimeMillis() % 100000;
                }
                LifePaymentProvider created = new LifePaymentProvider();
                created.setServiceType(serviceType);
                created.setCityName(cityName);
                created.setCityCode(cityCode);
                created.setProviderName(providerName);
                created.setProviderCode(providerCode);
                created.setProvinceName(item.provinceName());
                created.setSource(source);
                created.setEnabled(true);
                created.setLastCapturedAt(now);
                providerRepository.save(created);
                inserted++;
            }
        }
        auditService.log(http, admin.username(), "life_payment.provider.import", null,
            Map.of("serviceType", serviceType.name(), "inserted", inserted, "updated", updated, "skipped", skipped));
        return new ImportProvidersResponse(inserted, updated, skipped);
    }

    private OrderItem toOrderItem(LifePaymentOrder order) {
        String accountNo = "";
        String providerName = "";
        String cityName = "";
        String executionStatus = "";
        String receipt = "";
        if (order.getServiceType() == ServiceType.mobile) {
            LifePaymentMobileDetail d = mobileDetailRepository.findByOrderNo(order.getOrderNo()).orElse(null);
            if (d != null) {
                accountNo = d.getPhone();
                executionStatus = d.getRechargeStatus() == null ? "" : d.getRechargeStatus();
                receipt = d.getReceipt() == null ? "" : d.getReceipt();
            }
        } else {
            LifePaymentUtilityDetail d = utilityDetailRepository.findByOrderNo(order.getOrderNo()).orElse(null);
            if (d != null) {
                accountNo = d.getAccountNo();
                providerName = d.getProviderName();
                cityName = d.getCityName();
                executionStatus = d.getUtilityStatus() == null ? "" : d.getUtilityStatus();
                receipt = d.getReceipt() == null ? "" : d.getReceipt();
            }
        }
        String taskNo = taskRepository.findFirstByOrderNoAndStatusInOrderByCreatedAtDesc(
                order.getOrderNo(),
                List.of(TaskStatus.ready, TaskStatus.running, TaskStatus.success, TaskStatus.failed, TaskStatus.need_manual))
            .map(LifePaymentTask::getTaskNo)
            .orElse(null);
        return new OrderItem(
            order.getOrderNo(),
            order.getClientOrderId(),
            order.getUserId(),
            order.getServiceType().name(),
            order.getAmount(),
            order.getPayMethod().name(),
            order.getPlatformPayStatus().name(),
            order.getPluginStatus().name(),
            order.getOrderStatus().name(),
            accountNo,
            providerName,
            cityName,
            executionStatus,
            receipt,
            taskNo,
            LifePaymentSupport.formatTime(order.getPaidAt()),
            LifePaymentSupport.formatTime(order.getCreatedAt()),
            LifePaymentSupport.formatTime(order.getUpdatedAt())
        );
    }

    private TaskItem toTaskItem(LifePaymentTask task) {
        Map<String, Object> payload = LifePaymentSupport.parseJsonMap(task.getPayloadJson());
        return new TaskItem(
            task.getTaskNo(),
            task.getOrderNo(),
            task.getQueryNo(),
            task.getServiceType().name(),
            task.getTaskAction().name(),
            task.getPaymentStatus(),
            task.getStatus().name(),
            task.getAttemptCount(),
            task.getMaxAttempts(),
            task.getLockedBy(),
            LifePaymentSupport.formatTime(task.getLockedAt()),
            LifePaymentSupport.formatTime(task.getHeartbeatAt()),
            task.getLastErrorCode(),
            task.getLastErrorMessage(),
            payload,
            LifePaymentSupport.formatTime(task.getCreatedAt()),
            LifePaymentSupport.formatTime(task.getUpdatedAt()),
            LifePaymentSupport.formatTime(task.getFinishedAt())
        );
    }

    private LogItem toLogItem(LifePaymentOperationLog log) {
        return new LogItem(
            LifePaymentSupport.formatTime(log.getCreatedAt()),
            log.getActorType() == null ? null : log.getActorType().name(),
            log.getActorId(),
            log.getAction(),
            log.getMessage(),
            log.getTaskNo(),
            log.getServiceType() == null ? null : log.getServiceType().name()
        );
    }

    private WorkerItem toWorkerItem(LifePaymentWorkerDevice worker) {
        return new WorkerItem(
            worker.getWorkerId(),
            worker.getDeviceId(),
            worker.getDeviceName(),
            LifePaymentSupport.parseServiceTypesCsv(worker.getSupportServiceTypes()).stream()
                .map(Enum::name).toList(),
            worker.getStatus().name(),
            worker.getAppVersion(),
            LifePaymentSupport.formatTime(worker.getLastOnlineAt()),
            LifePaymentSupport.formatTime(worker.getLastHeartbeatAt()),
            LifePaymentSupport.formatTime(worker.getLastOfflineAt()),
            worker.getWorkerTokenHash() != null && !worker.getWorkerTokenHash().isBlank()
        );
    }

    private ProviderItem toProviderItem(LifePaymentProvider provider) {
        return new ProviderItem(
            provider.getProviderCode(),
            provider.getServiceType().name(),
            provider.getCountryCode(),
            provider.getProvinceName(),
            provider.getCityName(),
            provider.getCityCode(),
            provider.getProviderName(),
            provider.getProviderAlias(),
            provider.getSource(),
            provider.isEnabled(),
            LifePaymentSupport.formatTime(provider.getLastCapturedAt()),
            LifePaymentSupport.formatTime(provider.getUpdatedAt())
        );
    }

    private static ServiceType parseServiceTypeOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ServiceType.require(raw);
        } catch (Exception e) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "invalid service_type");
        }
    }

    private static OrderStatus parseOrderStatusOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OrderStatus.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "invalid order_status");
        }
    }

    private static TaskStatus parseTaskStatusOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return TaskStatus.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "invalid status");
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public record ReasonRequest(String reason) {}

    public record EnabledRequest(Boolean enabled) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ImportProviderItem(
        String cityName,
        String cityCode,
        String providerName,
        String providerCode,
        String provinceName
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ImportProvidersRequest(
        String serviceType,
        String source,
        List<ImportProviderItem> items
    ) {}

    public record ImportProvidersResponse(int inserted, int updated, int skipped) {}

    public record ProviderEnabledResponse(String providerCode, boolean enabled) {}

    public record PageResponse<T>(
        List<T> items,
        long total,
        int page,
        int pageSize,
        boolean hasMore
    ) {}

    public record OrderItem(
        String orderNo,
        String clientOrderId,
        String userId,
        String serviceType,
        java.math.BigDecimal amount,
        String payMethod,
        String platformPayStatus,
        String pluginStatus,
        String orderStatus,
        String accountNo,
        String providerName,
        String cityName,
        String executionStatus,
        String receipt,
        String taskNo,
        String paidAt,
        String createdAt,
        String updatedAt
    ) {}

    public record TaskItem(
        String taskNo,
        String orderNo,
        String queryNo,
        String serviceType,
        String taskAction,
        String paymentStatus,
        String status,
        int attemptCount,
        int maxAttempts,
        String lockedBy,
        String lockedAt,
        String heartbeatAt,
        String lastErrorCode,
        String lastErrorMessage,
        Map<String, Object> payloadJson,
        String createdAt,
        String updatedAt,
        String finishedAt
    ) {}

    public record LogItem(
        String createdAt,
        String actorType,
        String actorId,
        String action,
        String message,
        String taskNo,
        String serviceType
    ) {}

    public record LogListResponse(List<LogItem> items) {}

    public record WorkerItem(
        String workerId,
        String deviceId,
        String deviceName,
        List<String> supportServiceTypes,
        String status,
        String appVersion,
        String lastOnlineAt,
        String lastHeartbeatAt,
        String lastOfflineAt,
        boolean hasToken
    ) {}

    public record WorkerListResponse(List<WorkerItem> items) {}

    public record IssueWorkerTokenRequest(
        String deviceId,
        String deviceName,
        List<String> supportServiceTypes,
        String remark
    ) {}

    public record IssueWorkerTokenResponse(
        String workerId,
        String workerToken,
        String deviceName,
        List<String> supportServiceTypes,
        boolean tokenIssued
    ) {}

    public record ProviderItem(
        String providerCode,
        String serviceType,
        String countryCode,
        String provinceName,
        String cityName,
        String cityCode,
        String providerName,
        String providerAlias,
        String source,
        boolean enabled,
        String lastCapturedAt,
        String updatedAt
    ) {}
}
