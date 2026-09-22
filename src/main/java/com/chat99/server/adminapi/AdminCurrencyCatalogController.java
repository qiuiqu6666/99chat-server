package com.chat99.server.adminapi;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/currencies")
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AdminCurrencyCatalogController {

    private final AdminCurrencyCatalogService currencyCatalogService;

    public AdminCurrencyCatalogController(AdminCurrencyCatalogService currencyCatalogService) {
        this.currencyCatalogService = currencyCatalogService;
    }

    @GetMapping
    public AdminCurrencyCatalogService.CurrencyListResponse list(Authentication auth) {
        AdminAccess.requirePermission(auth, "wallet.read");
        return currencyCatalogService.list();
    }

    @PutMapping("/{code}")
    public AdminCurrencyCatalogService.CurrencyItem update(
            HttpServletRequest http,
            Authentication auth,
            @PathVariable String code,
            @RequestBody AdminCurrencyCatalogService.UpdateBody body) {
        return currencyCatalogService.update(http, auth, code, body);
    }

    @PostMapping("/upload-logo")
    public AdminCurrencyCatalogService.LogoUploadResult uploadLogo(
            Authentication auth,
            @RequestParam("file") MultipartFile file) {
        return currencyCatalogService.uploadLogo(auth, file);
    }
}
