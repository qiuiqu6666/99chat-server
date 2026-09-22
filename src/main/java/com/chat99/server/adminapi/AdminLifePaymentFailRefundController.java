package com.chat99.server.adminapi;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/life-payments")
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AdminLifePaymentFailRefundController {

    private final AdminLifePaymentFailRefundService service;

    public AdminLifePaymentFailRefundController(AdminLifePaymentFailRefundService service) {
        this.service = service;
    }

    @PostMapping("/orders/{order_no}/fail-refund")
    public Map<String, Object> markFailedAndRefund(
        HttpServletRequest http,
        Authentication auth,
        @PathVariable("order_no") String orderNo,
        @RequestBody(required = false) AdminLifePaymentFailRefundService.ReasonRequest body) {
        return service.markFailedAndRefund(http, auth, orderNo, body);
    }
}
