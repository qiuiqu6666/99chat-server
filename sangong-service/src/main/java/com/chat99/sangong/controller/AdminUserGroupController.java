package com.chat99.sangong.controller;

import com.chat99.sangong.domain.SangongUser;
import com.chat99.sangong.repository.UserRepository;
import com.chat99.sangong.service.UserGroupService;
import com.chat99.sangong.service.UserService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminUserGroupController {
    private final UserGroupService groups;
    private final UserService users;
    private final UserRepository userRepo;

    public AdminUserGroupController(UserGroupService groups, UserService users, UserRepository userRepo) {
        this.groups = groups;
        this.users = users;
        this.userRepo = userRepo;
    }

    @GetMapping("/user-groups")
    public Map<String, Object> index() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("groups", groups.listGroups());
        return out;
    }

    @PostMapping("/user-groups")
    public ResponseEntity<Map<String, Object>> store(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> group;
        try {
            group = groups.create(Req.body(body));
        } catch (RuntimeException e) {
            return PlayerBetController.err(422, "GROUP_INVALID", e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("group", group);
        return ResponseEntity.status(201).body(out);
    }

    @PutMapping("/user-groups/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable long id,
                                                      @RequestBody(required = false) Map<String, Object> body) {
        if (groups.findById(id) == null) {
            return PlayerBetController.err(404, "NOT_FOUND", "分组不存在");
        }
        Map<String, Object> group;
        try {
            group = groups.update(id, Req.body(body));
        } catch (RuntimeException e) {
            return PlayerBetController.err(422, "GROUP_INVALID", e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("group", group);
        return ResponseEntity.ok(out);
    }

    @PutMapping("/users/{userId}/group")
    public ResponseEntity<Map<String, Object>> assignUserById(@PathVariable long userId,
                                                              @RequestBody(required = false) Map<String, Object> body) {
        return assign(body, userId);
    }

    @RequestMapping(value = "/users/group",
        method = {org.springframework.web.bind.annotation.RequestMethod.PUT,
                  org.springframework.web.bind.annotation.RequestMethod.POST})
    public ResponseEntity<Map<String, Object>> assignUser(@RequestBody(required = false) Map<String, Object> body) {
        return assign(body, null);
    }

    private ResponseEntity<Map<String, Object>> assign(Map<String, Object> body, Long userId) {
        SangongUser user = resolveUser(body, userId);
        if (user == null) {
            return PlayerBetController.err(422, "INVALID_REQUEST", "请提供 userId 或 imUserId");
        }

        boolean groupCreated = false;
        Map<String, Object> assignedGroup = null;
        Long resolvedGroupId = null;
        boolean hasRebatePct = Req.has(body, "playerRebatePct") || Req.has(body, "playerRebatePer10000");
        boolean rebatePer10000 = Req.has(body, "playerRebatePer10000");
        double playerRebatePct = 0d;
        if (hasRebatePct) {
            try {
                playerRebatePct = Double.parseDouble(Req.str(body,
                    rebatePer10000 ? "playerRebatePer10000" : "playerRebatePct", "0").trim());
                if (rebatePer10000) playerRebatePct = playerRebatePct / 100.0d;
                if (playerRebatePct < 0) {
                    return PlayerBetController.err(422, "INVALID_PCT", "playerRebatePct 必须 ≥ 0");
                }
            } catch (NumberFormatException e) {
                return PlayerBetController.err(422, "INVALID_PCT", "playerRebatePct 格式无效");
            }
        }

        if (Req.has(body, "group") || Req.has(body, "groupCode")) {
            String code = Req.str(body, "group", Req.str(body, "groupCode", "")).trim();
            try {
                Map<String, Object> result = groups.assignUserByCode(user, code);
                user = (SangongUser) result.get("user");
                groupCreated = Boolean.TRUE.equals(result.get("groupCreated"));
                assignedGroup = (Map<String, Object>) result.get("group");
                if (assignedGroup != null && assignedGroup.get("groupId") instanceof Number n) {
                    resolvedGroupId = n.longValue();
                }
            } catch (RuntimeException e) {
                return PlayerBetController.err(422, "GROUP_ASSIGN_FAILED", e.getMessage());
            }
        } else {
            Long groupId = null;
            if (Req.has(body, "groupId")) {
                String raw = Req.str(body, "groupId", "");
                if (!raw.isEmpty() && !"null".equals(raw)) {
                    try {
                        groupId = Long.parseLong(raw.trim());
                    } catch (NumberFormatException e) {
                        groupId = 0L;
                    }
                }
            }
            try {
                user = groups.assignUser(user, groupId);
            } catch (RuntimeException e) {
                return PlayerBetController.err(422, "GROUP_ASSIGN_FAILED", e.getMessage());
            }
            resolvedGroupId = groupId;
        }

        // rebate% 上限校验
        if (hasRebatePct && resolvedGroupId != null && resolvedGroupId > 0) {
            try {
                groups.validatePlayerRebateWithinGroup(resolvedGroupId, playerRebatePct);
            } catch (RuntimeException e) {
                return PlayerBetController.err(422, "REBATE_PCT_TOO_HIGH", e.getMessage());
            }
        }

        // 写 rebate%
        if (hasRebatePct) {
            if (resolvedGroupId != null && resolvedGroupId > 0) {
                userRepo.updateGroupIdAndRebatePct(user.getId(), resolvedGroupId, playerRebatePct);
            } else {
                userRepo.updatePlayerRebatePct(user.getId(), playerRebatePct);
            }
            user = userRepo.findById(user.getId()).orElse(user);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("user", formatUser(user));
        if (assignedGroup != null) {
            out.put("group", assignedGroup);
            out.put("groupCreated", groupCreated);
        }
        return ResponseEntity.ok(out);
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
        out.put("playerRebatePct", user.getPlayerRebatePct());
        out.put("playerRebatePer10000", Math.round(user.getPlayerRebatePct() * 100.0d));
        return out;
    }
}
