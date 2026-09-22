package com.chat99.server.adminapi;

import com.chat99.server.wallet.WithdrawService;
import com.chat99.server.wallet.WalletWithdrawal;
import com.chat99.server.wallet.WithdrawalStatus;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminWithdrawActionService {

    private final WithdrawService withdrawService;
    private final AdminAuditService auditService;
    private final AdminWithdrawAuditTotpService withdrawAuditTotpService;

    public AdminWithdrawActionService(WithdrawService withdrawService,
                                      AdminAuditService auditService,
                                      AdminWithdrawAuditTotpService withdrawAuditTotpService) {
        this.withdrawService = withdrawService;
        this.auditService = auditService;
        this.withdrawAuditTotpService = withdrawAuditTotpService;
    }

    @Transactional
    public FinanceActionResult approve(HttpServletRequest http, Authentication auth, String orderNo,
                                       String remark, String totpCode) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        withdrawAuditTotpService.requireValid(totpCode);
        WalletWithdrawal w = withdrawService.approveById(parseOrderNo(orderNo));
        auditService.log(http, admin.username(), "wallet.withdraw.approve", w.getUserId(),
            Map.of("withdraw_id", w.getId(), "remark", remark == null ? "" : remark));
        return toResult(w);
    }

    @Transactional
    public FinanceActionResult markPaidManually(HttpServletRequest http, Authentication auth, String orderNo,
                                                String txId, String remark, String totpCode) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        withdrawAuditTotpService.requireValid(totpCode);
        WalletWithdrawal w = withdrawService.markPaidManually(parseOrderNo(orderNo), txId);
        auditService.log(http, admin.username(), "wallet.withdraw.manual_complete", w.getUserId(),
            Map.of(
                "withdraw_id", w.getId(),
                "tx_id", w.getTxId() == null ? "" : w.getTxId(),
                "remark", remark == null ? "" : remark));
        return toResult(w);
    }

    @Transactional
    public FinanceActionResult reject(HttpServletRequest http, Authentication auth, String orderNo,
                                      String remark, String totpCode) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        withdrawAuditTotpService.requireValid(totpCode);
        WalletWithdrawal w = withdrawService.rejectById(parseOrderNo(orderNo), remark);
        auditService.log(http, admin.username(), "wallet.withdraw.reject", w.getUserId(),
            Map.of("withdraw_id", w.getId(), "remark", remark == null ? "" : remark));
        return toResult(w);
    }

    private static long parseOrderNo(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "order_no required");
        }
        try {
            return Long.parseLong(orderNo.trim());
        } catch (NumberFormatException e) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "invalid order_no");
        }
    }

    private static FinanceActionResult toResult(WalletWithdrawal w) {
        return new FinanceActionResult(
            true,
            w.getUserId(),
            "USDT",
            AdminUserFormats.decimalFromMicro(w.getAmountMicro()),
            null,
            null,
            String.valueOf(w.getId()),
            String.valueOf(w.getId()),
            mapStatus(w.getStatus()));
    }

    private static String mapStatus(WithdrawalStatus status) {
        return switch (status) {
            case PENDING -> "待审核";
            case BROADCASTING, CONFIRMING -> "处理中";
            case COMPLETED -> "成功";
            case FAILED -> "失败";
        };
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FinanceActionResult(
        boolean ok,
        String userUid,
        String currency,
        String amount,
        String balanceBefore,
        String balanceAfter,
        String transactionNo,
        String orderNo,
        String status) {}
}
