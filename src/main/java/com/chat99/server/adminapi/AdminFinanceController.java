package com.chat99.server.adminapi;

import com.chat99.server.wallet.WalletConfigService;
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
@RequestMapping("/api/v1")
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AdminFinanceController {

    private final AdminFinanceService adminFinanceService;
    private final AdminWithdrawActionService adminWithdrawActionService;
    private final AdminWithdrawAuditTotpService withdrawAuditTotpService;
    private final WalletConfigService walletConfigService;

    public AdminFinanceController(AdminFinanceService adminFinanceService,
                                  AdminWithdrawActionService adminWithdrawActionService,
                                  AdminWithdrawAuditTotpService withdrawAuditTotpService,
                                  WalletConfigService walletConfigService) {
        this.adminFinanceService = adminFinanceService;
        this.adminWithdrawActionService = adminWithdrawActionService;
        this.withdrawAuditTotpService = withdrawAuditTotpService;
        this.walletConfigService = walletConfigService;
    }

    @GetMapping("/wallet/transactions")
    public AdminFinanceService.FinanceListResponse listWalletTransactions(
            Authentication auth,
            @RequestParam(name = "user_uid", required = false) String userUid,
            @RequestParam(name = "transaction_type", required = false) String transactionType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "create_time_desc") String sort) {
        AdminAccess.requirePermission(auth, "wallet.read");
        return adminFinanceService.listLedger(userUid, transactionType, status, page, pageSize, sort);
    }

    @GetMapping("/wallet/ledger")
    public AdminFinanceService.FinanceListResponse listWalletLedger(
            Authentication auth,
            @RequestParam(name = "user_uid", required = false) String userUid,
            @RequestParam(name = "transaction_type", required = false) String transactionType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "create_time_desc") String sort) {
        AdminAccess.requirePermission(auth, "wallet.read");
        return adminFinanceService.listLedger(userUid, transactionType, status, page, pageSize, sort);
    }

    @GetMapping("/recharges")
    public AdminFinanceService.FinanceListResponse listRecharges(
            Authentication auth,
            @RequestParam(name = "user_uid", required = false) String userUid,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "create_time_desc") String sort) {
        AdminAccess.requirePermission(auth, "wallet.read");
        return adminFinanceService.listRecharges(userUid, keyword, status, page, pageSize, sort);
    }

    @GetMapping("/withdraws")
    public AdminFinanceService.FinanceListResponse listWithdraws(
            Authentication auth,
            @RequestParam(name = "user_uid", required = false) String userUid,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "create_time_desc") String sort) {
        AdminAccess.requirePermission(auth, "wallet.read");
        return adminFinanceService.listWithdrawals(userUid, keyword, status, page, pageSize, sort);
    }

    @GetMapping("/transfers")
    public AdminFinanceService.FinanceListResponse listTransfers(
            Authentication auth,
            @RequestParam(name = "user_uid", required = false) String userUid,
            @RequestParam(name = "transaction_type", required = false) String transactionType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "create_time_desc") String sort) {
        AdminAccess.requirePermission(auth, "wallet.read");
        return adminFinanceService.listTransfers(
                userUid, transactionType, status, keyword, page, pageSize, sort);
    }

    @GetMapping("/exchanges")
    public AdminFinanceService.FinanceListResponse listExchanges(
            Authentication auth,
            @RequestParam(name = "user_uid", required = false) String userUid,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "create_time_desc") String sort) {
        AdminAccess.requirePermission(auth, "wallet.read");
        return adminFinanceService.listExchanges(userUid, direction, keyword, page, pageSize, sort);
    }

    @GetMapping("/red-packets")
    public AdminFinanceService.FinanceListResponse listRedPackets(
            Authentication auth,
            @RequestParam(name = "user_uid", required = false) String userUid,
            @RequestParam(name = "involves_user_uid", required = false) String involvesUserUid,
            @RequestParam(name = "sender_uid", required = false) String senderUid,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "create_time_desc") String sort) {
        AdminAccess.requirePermission(auth, "wallet.read");
        String uid = firstNonBlank(userUid, involvesUserUid, senderUid);
        return adminFinanceService.listRedPackets(uid, keyword, status, page, pageSize, sort);
    }

    @GetMapping("/withdraws/audit-totp/status")
    public WithdrawAuditStatusResponse withdrawAuditTotpStatus(Authentication auth) {
        AdminAccess.requirePermission(auth, "wallet.read");
        return new WithdrawAuditStatusResponse(
            withdrawAuditTotpService.status().configured(),
            walletConfigService.getWithdrawMode());
    }

    @PostMapping("/withdraws/audit-totp/setup")
    public AdminWithdrawAuditTotpService.TotpSetupResponse withdrawAuditTotpSetup(Authentication auth) {
        return withdrawAuditTotpService.beginSetup(auth);
    }

    @PostMapping("/withdraws/audit-totp/confirm")
    public AdminWithdrawAuditTotpService.TotpStatusResponse withdrawAuditTotpConfirm(
            Authentication auth,
            @RequestBody TotpConfirmRequest body) {
        String code = body == null ? null : body.totpCode();
        return withdrawAuditTotpService.confirmSetup(auth, code);
    }

    @PostMapping("/withdraws/audit-totp/reset")
    public AdminWithdrawAuditTotpService.TotpStatusResponse withdrawAuditTotpReset(
            Authentication auth,
            @RequestBody TotpConfirmRequest body) {
        String code = body == null ? null : body.totpCode();
        return withdrawAuditTotpService.reset(auth, code);
    }

    @PostMapping("/withdraws/{orderNo}/approve")
    public AdminWithdrawActionService.FinanceActionResult approveWithdraw(
            HttpServletRequest http,
            Authentication auth,
            @PathVariable String orderNo,
            @RequestBody(required = false) WithdrawActionRequest body) {
        String remark = body == null ? null : body.remark();
        String totpCode = body == null ? null : body.totpCode();
        return adminWithdrawActionService.approve(http, auth, orderNo, remark, totpCode);
    }

    @PostMapping("/withdraws/{orderNo}/manual-complete")
    public AdminWithdrawActionService.FinanceActionResult manualCompleteWithdraw(
            HttpServletRequest http,
            Authentication auth,
            @PathVariable String orderNo,
            @RequestBody(required = false) WithdrawActionRequest body) {
        String remark = body == null ? null : body.remark();
        String totpCode = body == null ? null : body.totpCode();
        String txId = body == null ? null : body.txId();
        return adminWithdrawActionService.markPaidManually(http, auth, orderNo, txId, remark, totpCode);
    }

    @PostMapping("/withdraws/{orderNo}/reject")
    public AdminWithdrawActionService.FinanceActionResult rejectWithdraw(
            HttpServletRequest http,
            Authentication auth,
            @PathVariable String orderNo,
            @RequestBody(required = false) WithdrawActionRequest body) {
        String remark = body == null ? null : body.remark();
        String totpCode = body == null ? null : body.totpCode();
        return adminWithdrawActionService.reject(http, auth, orderNo, remark, totpCode);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record WithdrawActionRequest(String remark, String totpCode, String txId) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record WithdrawAuditStatusResponse(boolean configured, String withdrawMode) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TotpConfirmRequest(String totpCode) {}

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }
}
