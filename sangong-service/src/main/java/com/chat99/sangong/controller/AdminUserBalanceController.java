package com.chat99.sangong.controller;

import com.chat99.sangong.domain.SangongLedger;
import com.chat99.sangong.domain.SangongSession;
import com.chat99.sangong.domain.SangongUser;
import com.chat99.sangong.service.BalanceService;
import com.chat99.sangong.service.DebitGuardService;
import com.chat99.sangong.service.ImService;
import com.chat99.sangong.service.MainUserProfileService;
import com.chat99.sangong.service.SessionService;
import com.chat99.sangong.service.UserGroupService;
import com.chat99.sangong.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserBalanceController {
    private final UserService users;
    private final BalanceService balance;
    private final UserGroupService groups;
    private final SessionService sessions;
    private final DebitGuardService debitGuard;
    private final ImService im;
    private final MainUserProfileService profiles;

    public AdminUserBalanceController(UserService users, BalanceService balance, UserGroupService groups,
                                      SessionService sessions, DebitGuardService debitGuard, ImService im,
                                      MainUserProfileService profiles) {
        this.users = users;
        this.balance = balance;
        this.groups = groups;
        this.sessions = sessions;
        this.debitGuard = debitGuard;
        this.im = im;
        this.profiles = profiles;
    }

    @PostMapping("/credit")
    public ResponseEntity<Map<String, Object>> credit(@RequestBody(required = false) Map<String, Object> body,
                                                       HttpServletRequest request) {
        return adjust(body, null, "credit", request);
    }

    @PostMapping("/debit")
    public ResponseEntity<Map<String, Object>> debit(@RequestBody(required = false) Map<String, Object> body,
                                                      HttpServletRequest request) {
        return adjust(body, null, "debit", request);
    }

    @PostMapping("/{userId}/credit")
    public ResponseEntity<Map<String, Object>> creditById(@PathVariable long userId,
                                                          @RequestBody(required = false) Map<String, Object> body,
                                                          HttpServletRequest request) {
        return adjust(body, userId, "credit", request);
    }

    @PostMapping("/{userId}/debit")
    public ResponseEntity<Map<String, Object>> debitById(@PathVariable long userId,
                                                         @RequestBody(required = false) Map<String, Object> body,
                                                         HttpServletRequest request) {
        return adjust(body, userId, "debit", request);
    }

    private ResponseEntity<Map<String, Object>> adjust(Map<String, Object> body, Long userId, String action,
                                                        HttpServletRequest request) {
        long amount = Req.lng(body, "amount", 0);
        if (amount <= 0) {
            return PlayerBetController.err(422, "INVALID_AMOUNT", "金额须为正整数");
        }
        SangongUser user = resolveUser(body, userId);
        if (user == null) {
            return PlayerBetController.err(404, "USER_NOT_FOUND", "用户不存在，请提供 userId 或 imUserId");
        }

        String note = Req.str(body, "note", "");
        Object authenticatedOperator = request.getAttribute("admin_user_id");
        String operator = authenticatedOperator == null ? "" : String.valueOf(authenticatedOperator).trim();
        if (operator.isEmpty()) {
            return PlayerBetController.err(401, "UNAUTHORIZED", "未识别群主或帮工账号");
        }
        SangongSession session = sessions.getRunning();
        Long sessionId = session != null ? session.getId() : null;
        String ledgerType = "credit".equals(action) ? "admin_credit" : "admin_debit";

        if ("debit".equals(action)) {
            Map<String, Object> check = debitGuard.validateDebit(user, amount);
            if (!Boolean.TRUE.equals(check.get("ok"))) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", false);
                out.put("code", check.get("code"));
                out.put("message", check.get("message"));
                out.put("balance", check.get("balance"));
                if (check.containsKey("periodNo")) {
                    out.put("periodNo", check.get("periodNo"));
                }
                return ResponseEntity.status(422).body(out);
            }
        }

        BalanceService.BalanceResult result;
        try {
            result = "credit".equals(action)
                ? balance.credit(user, amount, ledgerType, sessionId, note, null, null, operator)
                : balance.debit(user, amount, ledgerType, sessionId, note, null, null, operator);
        } catch (RuntimeException e) {
            return PlayerBetController.err(422, "INVALID_AMOUNT", e.getMessage());
        }

        SangongUser updated = result.user();
        SangongLedger ledger = result.ledger();

        String operatorDisplay = profiles.getNickname(operator);
        if (operatorDisplay == null || operatorDisplay.isBlank()) {
            operatorDisplay = operator;
        } else {
            operatorDisplay = operatorDisplay.trim() + "(" + operator + ")";
        }
        im.notifyAdminLedgerAdjust(action, users.resolveNickname(updated), amount,
            updated.getBalance(), operatorDisplay, note, ledger.getId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("ledger", formatLedger(ledger, action, amount));
        out.put("user", formatUser(updated));
        return ResponseEntity.ok(out);
    }

    private Map<String, Object> formatLedger(SangongLedger ledger, String action, long amount) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ledgerId", ledger.getId());
        out.put("type", action);
        out.put("typeLabel", "credit".equals(action) ? "上分" : "下分");
        out.put("amount", amount);
        out.put("balanceAfter", ledger.getBalanceAfter());
        out.put("operator", ledger.getOperator() == null ? "" : ledger.getOperator());
        out.put("note", ledger.getNote() == null ? "" : ledger.getNote());
        return out;
    }

    private SangongUser resolveUser(Map<String, Object> body, Long userId) {
        if (userId != null && userId > 0) {
            return users.findById(userId);
        }
        long bodyUserId = Req.lng(body, "userId", 0);
        if (bodyUserId > 0) {
            return users.findById(bodyUserId);
        }
        String imUserId = Req.str(body, "imUserId", "").trim();
        if (imUserId.isEmpty()) {
            return null;
        }
        String nickname = Req.has(body, "nickname") ? Req.str(body, "nickname", null) : null;
        return users.findOrCreateByImUserId(imUserId, nickname);
    }

    private Map<String, Object> formatUser(SangongUser user) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", user.getId());
        out.put("imUserId", user.getImUserId());
        out.put("nickname", user.getNickname());
        out.put("balance", user.getBalance());
        out.put("group", groups.formatUserGroup(user.getGroupId()));
        return out;
    }
}
