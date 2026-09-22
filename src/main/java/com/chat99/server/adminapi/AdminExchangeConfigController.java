package com.chat99.server.adminapi;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wallet/exchange-config")
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AdminExchangeConfigController {

    private final AdminExchangeConfigService exchangeConfigService;

    public AdminExchangeConfigController(AdminExchangeConfigService exchangeConfigService) {
        this.exchangeConfigService = exchangeConfigService;
    }

    @GetMapping
    public AdminExchangeConfigService.ExchangeConfigResponse get(Authentication auth) {
        AdminAccess.requirePermission(auth, "wallet.read");
        return exchangeConfigService.get();
    }

    @PutMapping
    public AdminExchangeConfigService.ExchangeConfigResponse update(
            HttpServletRequest http,
            Authentication auth,
            @RequestBody AdminExchangeConfigService.UpdateBody body) {
        return exchangeConfigService.update(http, auth, body);
    }
}
