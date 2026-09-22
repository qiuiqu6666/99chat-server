package com.chat99.sangong.controller;

import com.chat99.sangong.common.BusinessException;
import com.chat99.sangong.common.InsufficientBalanceException;
import com.chat99.sangong.domain.SangongUser;
import com.chat99.sangong.repository.UserRepository;
import com.chat99.sangong.service.AgentPrivilegeService;
import com.chat99.sangong.service.BetService;
import com.chat99.sangong.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminProxyBetController {
    private final BetService bets;
    private final UserService users;
    private final UserRepository userRepo;
    private final AgentPrivilegeService agentAuth;

    public AdminProxyBetController(BetService bets, UserService users, UserRepository userRepo,
                                   AgentPrivilegeService agentAuth) {
        this.bets = bets;
        this.users = users;
        this.userRepo = userRepo;
        this.agentAuth = agentAuth;
    }

    @PostMapping("/api/v1/admin/bets")
    public ResponseEntity<Map<String, Object>> store(@RequestBody(required = false) Map<String, Object> body,
                                                      HttpServletRequest request) {
        long userId = Req.lng(body, "userId", 0);
        int door = Req.intval(body, "door", 0);
        long amount = Req.lng(body, "amount", 0);

        if (userId <= 0 || door < 1 || amount <= 0) {
            return PlayerBetController.err(422, "INVALID_REQUEST", "参数无效");
        }
        SangongUser user = userRepo.findById(userId).orElse(null);
        if (user == null) {
            return PlayerBetController.err(404, "USER_NOT_FOUND", "用户不存在");
        }

        // 如果玩家绑了代理分组，要求当前账号必须是该代理本人（否则只允许 owner/admin 走群主代录路径）
        // 当前 GamePrivilegeFilter 已经保证 admin_user_id 是 game_privileged 用户
        if (user.getGroupId() != null) {
            try {
                agentAuth.assertCanProxyBet(request, user.getGroupId());
            } catch (IllegalStateException e) {
                return PlayerBetController.err(403, e.getMessage(), mapStateMsg(e.getMessage()));
            }
        }

        Map<String, Object> result;
        try {
            result = bets.placeBet(user, door, amount, true);
        } catch (InsufficientBalanceException e) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("code", "INSUFFICIENT_BALANCE");
            out.put("balance", e.getBalance());
            out.put("message", "用户余额不足，无法代录");
            return ResponseEntity.status(422).body(out);
        } catch (BusinessException e) {
            return PlayerBetController.err(e.getHttpStatus(), e.getCode(), e.getMessage());
        } catch (RuntimeException e) {
            return PlayerBetController.err(422, "BET_REJECTED", e.getMessage());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("userId", userId);
        out.put("door", result.get("door"));
        out.put("amount", result.get("amount"));
        out.put("doorTotal", result.get("doorTotal"));
        out.put("balance", result.get("balance"));
        return ResponseEntity.status(201).body(out);
    }

    private static String mapStateMsg(String code) {
        if (code == null) return "拒绝";
        switch (code) {
            case "NOT_AGENT_OF_GROUP": return "该玩家绑定了代理分组，仅该代理本人可代录";
            case "AGENT_GROUP_NOT_CONFIGURED": return "玩家所在分组未配置代理";
            default: return code;
        }
    }
}