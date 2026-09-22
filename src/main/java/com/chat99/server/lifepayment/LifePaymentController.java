package com.chat99.server.lifepayment;

import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/life-payments")
public class LifePaymentController {

    private final LifePaymentOrderService orderService;
    private final LifePaymentCatalogService catalogService;
    private final LifePaymentAccountService accountService;

    public LifePaymentController(LifePaymentOrderService orderService,
                                 LifePaymentCatalogService catalogService,
                                 LifePaymentAccountService accountService) {
        this.orderService = orderService;
        this.catalogService = catalogService;
        this.accountService = accountService;
    }

    @GetMapping("/home")
    public Map<String, Object> home(Authentication auth) {
        return orderService.home(userId(auth));
    }

    @GetMapping("/services")
    public Map<String, Object> services(Authentication auth) {
        userId(auth);
        return catalogService.services();
    }

    @GetMapping("/accounts/profile")
    public Map<String, Object> accountProfile(
        Authentication auth,
        @RequestParam("service_type") String serviceType,
        @RequestParam("account_no") String accountNo,
        @RequestParam(value = "city_code", required = false) String cityCode,
        @RequestParam(value = "provider_code", required = false) String providerCode) {
        userId(auth);
        return accountService.profile(serviceType, accountNo, cityCode, providerCode);
    }

    @GetMapping("/mobile/amount-options")
    public Map<String, Object> mobileAmountOptions(
        Authentication auth,
        @RequestParam(value = "phone", required = false) String phone,
        @RequestParam(value = "operator_name", required = false) String operatorName) {
        userId(auth);
        return catalogService.mobileAmountOptions(phone, operatorName);
    }

    @GetMapping("/providers")
    public Map<String, Object> providers(
        Authentication auth,
        @RequestParam("service_type") String serviceType,
        @RequestParam(value = "city_name", required = false) String cityName,
        @RequestParam(value = "city_code", required = false) String cityCode,
        @RequestParam(value = "keyword", required = false) String keyword,
        @RequestParam(value = "page", defaultValue = "1") int page,
        @RequestParam(value = "page_size", defaultValue = "50") int pageSize) {
        userId(auth);
        return catalogService.providers(serviceType, cityName, cityCode, keyword, page, pageSize);
    }

    @GetMapping("/utility/amount-options")
    public Map<String, Object> utilityAmountOptions(
        Authentication auth,
        @RequestParam("service_type") String serviceType,
        @RequestParam(value = "city_code", required = false) String cityCode,
        @RequestParam(value = "provider_code", required = false) String providerCode) {
        userId(auth);
        return catalogService.utilityAmountOptions(serviceType, cityCode, providerCode);
    }

    @PostMapping("/utility/queries")
    public Map<String, Object> createUtilityQuery(Authentication auth, @RequestBody Map<String, Object> body) {
        return orderService.createUtilityQuery(userId(auth), body);
    }

    @GetMapping("/utility/queries/{query_no}")
    public Map<String, Object> getUtilityQuery(Authentication auth, @PathVariable("query_no") String queryNo) {
        return orderService.getUtilityQuery(userId(auth), queryNo);
    }

    @PostMapping("/mobile/orders")
    public Map<String, Object> createMobileOrder(Authentication auth, @RequestBody Map<String, Object> body) {
        return orderService.createMobileOrder(userId(auth), body);
    }

    @PostMapping("/utility/orders")
    public Map<String, Object> createUtilityOrder(Authentication auth, @RequestBody Map<String, Object> body) {
        return orderService.createUtilityOrder(userId(auth), body);
    }

    @GetMapping("/orders/{order_no}")
    public Map<String, Object> getOrder(Authentication auth, @PathVariable("order_no") String orderNo) {
        return orderService.getOrder(userId(auth), orderNo);
    }

    @GetMapping("/orders")
    public Map<String, Object> listOrders(
        Authentication auth,
        @RequestParam(value = "service_type", required = false) String serviceType,
        @RequestParam(value = "order_status", required = false) String orderStatus,
        @RequestParam(value = "page", defaultValue = "1") int page,
        @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        return orderService.listOrders(userId(auth), serviceType, orderStatus, page, pageSize);
    }

    @PostMapping("/mobile/orders/{order_no}/owner-last-char")
    public Map<String, Object> supplementOwnerLastChar(
        Authentication auth,
        @PathVariable("order_no") String orderNo,
        @RequestBody Map<String, Object> body) {
        return orderService.supplementOwnerLastChar(userId(auth), orderNo, body);
    }

    @PostMapping("/orders/{order_no}/cancel")
    public Map<String, Object> cancelOrder(
        Authentication auth,
        @PathVariable("order_no") String orderNo,
        @RequestBody(required = false) Map<String, Object> body) {
        return orderService.cancelOrder(userId(auth), orderNo, body == null ? Map.of() : body);
    }

    private static String userId(Authentication auth) {
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            throw LifePaymentExceptions.forbidden("UNAUTHORIZED");
        }
        return auth.getName();
    }
}
