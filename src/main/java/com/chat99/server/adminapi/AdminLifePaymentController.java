package com.chat99.server.adminapi;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/life-payments")
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AdminLifePaymentController {

    private final AdminLifePaymentService service;

    public AdminLifePaymentController(AdminLifePaymentService service) {
        this.service = service;
    }

    @GetMapping("/orders")
    public AdminLifePaymentService.PageResponse<AdminLifePaymentService.OrderItem> listOrders(
        Authentication auth,
        @RequestParam(name = "user_uid", required = false) String userUid,
        @RequestParam(name = "order_no", required = false) String orderNo,
        @RequestParam(name = "service_type", required = false) String serviceType,
        @RequestParam(name = "order_status", required = false) String orderStatus,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        AdminAccess.requirePermission(auth, "wallet.read");
        return service.listOrders(userUid, orderNo, serviceType, orderStatus, page, pageSize);
    }

    @GetMapping("/orders/{order_no}")
    public AdminLifePaymentService.OrderItem getOrder(
        Authentication auth,
        @PathVariable("order_no") String orderNo) {
        AdminAccess.requirePermission(auth, "wallet.read");
        return service.getOrder(orderNo);
    }

    @GetMapping("/orders/{order_no}/logs")
    public AdminLifePaymentService.LogListResponse orderLogs(
        Authentication auth,
        @PathVariable("order_no") String orderNo) {
        AdminAccess.requirePermission(auth, "wallet.read");
        return service.listOrderLogs(orderNo);
    }

    @PostMapping("/orders/{order_no}/manual")
    public AdminLifePaymentService.OrderItem markManual(
        HttpServletRequest http,
        Authentication auth,
        @PathVariable("order_no") String orderNo,
        @RequestBody(required = false) AdminLifePaymentService.ReasonRequest body) {
        return service.markManual(http, auth, orderNo, body);
    }

    @PostMapping("/orders/{order_no}/fail-refund")
    public AdminLifePaymentService.OrderItem markFailedAndRefund(
        HttpServletRequest http,
        Authentication auth,
        @PathVariable("order_no") String orderNo,
        @RequestBody(required = false) AdminLifePaymentService.ReasonRequest body) {
        return service.markFailedAndRefund(http, auth, orderNo, body);
    }

    @GetMapping("/tasks")
    public AdminLifePaymentService.PageResponse<AdminLifePaymentService.TaskItem> listTasks(
        Authentication auth,
        @RequestParam(name = "task_no", required = false) String taskNo,
        @RequestParam(name = "order_no", required = false) String orderNo,
        @RequestParam(name = "service_type", required = false) String serviceType,
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        AdminAccess.requirePermission(auth, "wallet.read");
        return service.listTasks(taskNo, orderNo, serviceType, status, page, pageSize);
    }

    @GetMapping("/tasks/{task_no}")
    public AdminLifePaymentService.TaskItem getTask(
        Authentication auth,
        @PathVariable("task_no") String taskNo) {
        AdminAccess.requirePermission(auth, "wallet.read");
        return service.getTask(taskNo);
    }

    @PostMapping("/tasks/{task_no}/retry")
    public AdminLifePaymentService.TaskItem retryTask(
        HttpServletRequest http,
        Authentication auth,
        @PathVariable("task_no") String taskNo,
        @RequestBody(required = false) AdminLifePaymentService.ReasonRequest body) {
        return service.retryTask(http, auth, taskNo, body);
    }

    @GetMapping("/workers")
    public AdminLifePaymentService.WorkerListResponse listWorkers(
        Authentication auth,
        @RequestParam(required = false) String status) {
        AdminAccess.requirePermission(auth, "wallet.read");
        return service.listWorkers(status);
    }

    @PostMapping("/workers/{worker_id}/issue-token")
    public AdminLifePaymentService.IssueWorkerTokenResponse issueWorkerToken(
        HttpServletRequest http,
        Authentication auth,
        @PathVariable("worker_id") String workerId,
        @RequestBody(required = false) AdminLifePaymentService.IssueWorkerTokenRequest body) {
        return service.issueWorkerToken(http, auth, workerId, body);
    }

    @GetMapping("/providers")
    public AdminLifePaymentService.PageResponse<AdminLifePaymentService.ProviderItem> listProviders(
        Authentication auth,
        @RequestParam(name = "service_type", required = false) String serviceType,
        @RequestParam(name = "city_name", required = false) String cityName,
        @RequestParam(name = "city_code", required = false) String cityCode,
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) Boolean enabled,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        AdminAccess.requirePermission(auth, "wallet.read");
        return service.listProviders(serviceType, cityName, cityCode, keyword, enabled, page, pageSize);
    }

    @PostMapping("/providers/{provider_code}/enabled")
    public AdminLifePaymentService.ProviderEnabledResponse setProviderEnabled(
        HttpServletRequest http,
        Authentication auth,
        @PathVariable("provider_code") String providerCode,
        @RequestBody AdminLifePaymentService.EnabledRequest body) {
        return service.setProviderEnabled(http, auth, providerCode, body);
    }

    @PostMapping("/providers/import")
    public AdminLifePaymentService.ImportProvidersResponse importProviders(
        HttpServletRequest http,
        Authentication auth,
        @RequestBody AdminLifePaymentService.ImportProvidersRequest body) {
        return service.importProviders(http, auth, body);
    }
}
