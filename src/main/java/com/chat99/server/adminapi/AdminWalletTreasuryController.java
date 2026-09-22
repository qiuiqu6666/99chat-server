package com.chat99.server.adminapi;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wallet/treasury")
public class AdminWalletTreasuryController {

    private final AdminWalletTreasuryService treasuryService;
    private final AdminWalletSweepLogService sweepLogService;

    public AdminWalletTreasuryController(AdminWalletTreasuryService treasuryService,
                                         AdminWalletSweepLogService sweepLogService) {
        this.treasuryService = treasuryService;
        this.sweepLogService = sweepLogService;
    }

    @GetMapping("/summary")
    public AdminWalletTreasuryService.TreasurySummaryResponse summary(Authentication auth) {
        return treasuryService.summary(auth);
    }

    @GetMapping("/addresses")
    public AdminWalletTreasuryService.TreasuryListResponse list(
            Authentication auth,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(name = "refresh_chain", defaultValue = "false") boolean refreshChain) {
        AdminAccess.requirePermission(auth, "wallet.read");
        return treasuryService.list(keyword, page, pageSize, refreshChain);
    }

    @PostMapping("/collect/{userUid}")
    public AdminWalletTreasuryService.SweepActionResponse collectOne(
            HttpServletRequest http,
            Authentication auth,
            @PathVariable String userUid) {
        return treasuryService.collectOne(http, auth, userUid);
    }

    @PostMapping("/collect-all")
    public AdminWalletTreasuryService.CollectAllResponse collectAll(
            HttpServletRequest http,
            Authentication auth) {
        return treasuryService.collectAll(http, auth);
    }

    @GetMapping("/sweep-logs")
    public AdminWalletSweepLogService.SweepLogListResponse sweepLogs(
            Authentication auth,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String trigger,
            @RequestParam(name = "created_from", required = false) String createdFrom,
            @RequestParam(name = "created_to", required = false) String createdTo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        return sweepLogService.list(auth, keyword, status, trigger, createdFrom, createdTo, page, pageSize);
    }
}
