package com.chat99.sangong.controller;

import com.chat99.sangong.domain.SangongUser;
import com.chat99.sangong.service.BalanceService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {
    private final BalanceService balance;

    public MeController(BalanceService balance) {
        this.balance = balance;
    }

    @GetMapping("/api/v1/me/balance")
    public Map<String, Object> balance(HttpServletRequest request) {
        SangongUser user = (SangongUser) request.getAttribute("auth_user");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("userId", user.getId());
        out.put("imUserId", user.getImUserId());
        out.put("nickname", user.getNickname());
        out.put("balance", balance.getBalance(user));
        return out;
    }
}
