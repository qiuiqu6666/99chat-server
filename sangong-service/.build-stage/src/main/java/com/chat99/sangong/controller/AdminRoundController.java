package com.chat99.sangong.controller;

import com.chat99.sangong.common.InsufficientBalanceException;
import com.chat99.sangong.domain.SangongImMessage;
import com.chat99.sangong.domain.SangongRound;
import com.chat99.sangong.domain.SangongUser;
import com.chat99.sangong.repository.ImMessageRepository;
import com.chat99.sangong.service.BankerTextParser;
import com.chat99.sangong.service.BetWindowMessageOrder;
import com.chat99.sangong.service.DrawService;
import com.chat99.sangong.service.GameSettingsService;
import com.chat99.sangong.service.RoundService;
import com.chat99.sangong.service.SettleService;
import com.chat99.sangong.service.UserService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminRoundController {
    private final RoundService rounds;
    private final UserService users;
    private final GameSettingsService gameSettings;
    private final DrawService draws;
    private final SettleService settle;
    private final BankerTextParser bankerTextParser;
    private final BetWindowMessageOrder messageOrder;
    private final ImMessageRepository imMessages;

    public AdminRoundController(RoundService rounds, UserService users, GameSettingsService gameSettings,
                                DrawService draws, SettleService settle, BankerTextParser bankerTextParser,
                                BetWindowMessageOrder messageOrder, ImMessageRepository imMessages) {
        this.rounds = rounds;
        this.users = users;
        this.gameSettings = gameSettings;
        this.draws = draws;
        this.settle = settle;
        this.bankerTextParser = bankerTextParser;
        this.messageOrder = messageOrder;
        this.imMessages = imMessages;
    }

    // ===== 开新局 =====

    @PostMapping("/rounds")
    public ResponseEntity<Map<String, Object>> store() {
        SangongRound round;
        try {
            round = rounds.startNewRound();
        } catch (RuntimeException e) {
            return PlayerBetController.err(422, "ROUND_CREATE_FAILED", e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("round", rounds.formatRound(round));
        return ResponseEntity.status(201).body(out);
    }

    // ===== 定庄/设庄 =====

    @PostMapping("/rounds/{id}/banker")
    public ResponseEntity<Map<String, Object>> setBanker(@PathVariable long id,
                                                         @RequestBody(required = false) Map<String, Object> body) {
        SangongRound round = rounds.findById(id);
        if (round == null) {
            return PlayerBetController.err(404, "NOT_FOUND", "局不存在");
        }
        long userId = Req.lng(body, "userId", 0);
        if (userId <= 0) {
            return PlayerBetController.err(422, "INVALID_REQUEST", "缺少 userId");
        }
        try {
            round = rounds.setBanker(round, userId);
        } catch (RuntimeException e) {
            return PlayerBetController.err(422, "BANKER_FAILED", e.getMessage());
        }
        return okRound(round);
    }

    @PostMapping("/rounds/{id}/banker-door")
    public ResponseEntity<Map<String, Object>> setBankerDoor(@PathVariable long id,
                                                             @RequestBody(required = false) Map<String, Object> body) {
        SangongRound round = rounds.findById(id);
        if (round == null) {
            return PlayerBetController.err(404, "NOT_FOUND", "局不存在");
        }
        int door = Req.intval(body, "door", 0);
        try {
            round = rounds.setBankerDoor(round, door);
        } catch (RuntimeException e) {
            return PlayerBetController.err(422, "BANKER_DOOR_FAILED", e.getMessage());
        }
        return okRound(round);
    }

    /** 设庄：保存全局门数（2-10）。 */
    @RequestMapping(value = "/door-count",
        method = {org.springframework.web.bind.annotation.RequestMethod.POST,
                  org.springframework.web.bind.annotation.RequestMethod.PUT})
    public ResponseEntity<Map<String, Object>> setDoorCount(@RequestBody(required = false) Map<String, Object> body) {
        Integer doorCount = resolveDoorCountInput(body);
        if (doorCount == null) {
            return PlayerBetController.err(422, "INVALID_REQUEST", "请提供 doorCount 或 door（2-10）");
        }
        return saveDoorCount(doorCount);
    }

    @PostMapping({"/banker/setup", "/rounds/current/setup-banker"})
    public ResponseEntity<Map<String, Object>> setupBankerCurrent(@RequestBody(required = false) Map<String, Object> body) {
        return setupBanker(body, null);
    }

    @PostMapping("/rounds/{id}/setup-banker")
    public ResponseEntity<Map<String, Object>> setupBankerById(@PathVariable("id") String id,
                                                               @RequestBody(required = false) Map<String, Object> body) {
        return setupBanker(body, id);
    }

    private ResponseEntity<Map<String, Object>> setupBanker(Map<String, Object> body, String id) {
        long userId = Req.lng(body, "userId", 0);
        String imUserId = Req.str(body, "imUserId", "").trim();
        boolean hasUser = userId > 0 || !imUserId.isEmpty();

        if (!hasUser) {
            Integer doorCount = resolveDoorCountInput(body);
            if (doorCount != null) {
                return saveDoorCount(doorCount);
            }
            return PlayerBetController.err(422, "INVALID_REQUEST",
                "设庄请传 door/doorCount（2-10）；定庄请传 imUserId、door、limit");
        }

        SangongRound round = resolveSetupRound(id);
        if (round == null) {
            return PlayerBetController.err(404, "NOT_FOUND", "当前无可设置局");
        }

        int door = Req.intval(body, "door", 0);
        long bankerLimit = resolveBankerLimitInput(body);
        String nickname = Req.has(body, "nickname") ? Req.str(body, "nickname", null) : null;

        SangongUser user = userId > 0 ? users.findById(userId)
            : users.findOrCreateByImUserId(imUserId, nickname);
        if (user == null) {
            return PlayerBetController.err(404, "INVALID_REQUEST", "用户不存在");
        }
        if (door < 1) {
            return PlayerBetController.err(422, "INVALID_REQUEST", "门号无效");
        }

        boolean wasAwaitBanker = SangongRound.AWAIT_BANKER.equals(round.getStatus());

        RoundService.SetupResult result;
        try {
            result = rounds.setupBanker(round, user, door, bankerLimit);
        } catch (RuntimeException e) {
            return PlayerBetController.err(422, "SETUP_FAILED", e.getMessage());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("action", "setup_banker");
        out.put("newRound", result.newRound());
        out.put("replaced", !wasAwaitBanker && !result.newRound());
        out.put("round", rounds.formatRound(result.round()));
        return ResponseEntity.ok(out);
    }

    @PostMapping({"/banker/quick-setup", "/rounds/current/banker/quick-setup"})
    public ResponseEntity<Map<String, Object>> quickSetupCurrent(@RequestBody(required = false) Map<String, Object> body) {
        return quickSetup(body, null);
    }

    @PostMapping("/rounds/{id}/banker/quick-setup")
    public ResponseEntity<Map<String, Object>> quickSetupById(@PathVariable("id") String id,
                                                              @RequestBody(required = false) Map<String, Object> body) {
        return quickSetup(body, id);
    }

    /** 快速定庄：解析消息文本（如 4.9999、4/99999），定庄并推送群通知。 */
    private ResponseEntity<Map<String, Object>> quickSetup(Map<String, Object> body, String id) {
        Long messageId = Req.lngOrNull(body, "messageId");
        SangongImMessage message = null;
        if (messageId != null) {
            message = imMessages.findById(messageId).orElse(null);
            if (message == null) {
                return PlayerBetController.err(404, "NOT_FOUND", "消息不存在");
            }
        }

        String text = Req.str(body, "text", "").trim();
        if (text.isEmpty() && message != null) {
            text = message.getText() == null ? "" : message.getText().trim();
        }

        Integer door = Req.has(body, "door") ? Req.intval(body, "door", 0) : null;
        boolean hasLimit = Req.has(body, "limit") || Req.has(body, "amount");
        Long bankerLimit = hasLimit ? resolveBankerLimitInput(body) : null;
        String sourceText = text;
        Boolean limited = null;

        if (door == null || (bankerLimit == null && !hasLimit)) {
            if (text.isEmpty() && door == null) {
                return PlayerBetController.err(422, "INVALID_REQUEST", "请提供 messageId/text，或 door");
            }
            if (!text.isEmpty()) {
                BankerTextParser.ParsedBanker parsed = bankerTextParser.parse(text);
                if (parsed == null && door == null) {
                    return PlayerBetController.err(422, "INVALID_FORMAT",
                        "无法解析定庄文本，格式如 4、4.9999、4.5万、4；10000、4（10000）、4/99999");
                }
                if (parsed != null) {
                    if (door == null) {
                        door = parsed.door();
                    }
                    if (bankerLimit == null && !hasLimit) {
                        bankerLimit = (long) parsed.limit();
                        limited = parsed.limited();
                    }
                }
            }
        }

        if (door == null || door < 1) {
            return PlayerBetController.err(422, "INVALID_REQUEST", "门号无效");
        }
        if (bankerLimit == null) {
            bankerLimit = 0L;
        }
        if (bankerLimit < 0) {
            return PlayerBetController.err(422, "INVALID_REQUEST", "限额无效");
        }
        if (limited == null) {
            limited = bankerLimit > 0;
        }

        long userId = Req.lng(body, "userId", 0);
        String imUserId = Req.str(body, "imUserId", "").trim();
        String nickname = Req.has(body, "nickname") ? Req.str(body, "nickname", null) : null;

        if (userId <= 0 && imUserId.isEmpty() && message != null) {
            imUserId = message.getImUserId() == null ? "" : message.getImUserId().trim();
            if (nickname == null && message.getNickname() != null) {
                nickname = message.getNickname();
            }
        }
        if (userId <= 0 && imUserId.isEmpty()) {
            return PlayerBetController.err(422, "INVALID_REQUEST", "请提供 imUserId、userId 或 messageId");
        }

        SangongRound round = resolveSetupRound(id);
        if (round == null) {
            return PlayerBetController.err(404, "NOT_FOUND", "当前无可设置局");
        }

        SangongUser user = userId > 0 ? users.findById(userId)
            : users.findOrCreateByImUserId(imUserId, nickname);
        if (user == null) {
            return PlayerBetController.err(404, "INVALID_REQUEST", "用户不存在");
        }

        Map<String, Object> result;
        try {
            result = rounds.quickSetupBanker(round, user, door, bankerLimit, sourceText);
        } catch (RuntimeException e) {
            return PlayerBetController.err(422, "QUICK_SETUP_FAILED", e.getMessage());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("action", "quick_setup_banker");
        out.put("messageId", message != null ? message.getId() : null);
        out.put("newRound", result.get("newRound"));
        out.put("replaced", result.get("replaced"));
        out.put("sent", result.get("sent"));
        out.put("restarted", result.get("restarted"));
        out.put("parsed", result.get("parsed"));
        out.put("round", rounds.formatRound((SangongRound) result.get("round")));
        return ResponseEntity.ok(out);
    }

    // ===== 合庄 =====

    @PostMapping("/rounds/current/co-bank")
    public ResponseEntity<Map<String, Object>> addCoBankCurrent(@RequestBody(required = false) Map<String, Object> body) {
        return addCoBank(body, null);
    }

    @PostMapping("/rounds/{id}/co-bank")
    public ResponseEntity<Map<String, Object>> addCoBankById(@PathVariable("id") String id,
                                                             @RequestBody(required = false) Map<String, Object> body) {
        return addCoBank(body, id);
    }

    private ResponseEntity<Map<String, Object>> addCoBank(Map<String, Object> body, String id) {
        SangongRound round = resolveRound(id);
        if (round == null) {
            return PlayerBetController.err(404, "NOT_FOUND", "局不存在");
        }
        long userId = Req.lng(body, "userId", 0);
        long amount = Req.lng(body, "amount", 0);
        if (userId <= 0 || amount <= 0) {
            return PlayerBetController.err(422, "INVALID_REQUEST", "缺少 userId 或 amount");
        }
        try {
            round = rounds.addCoBank(round, userId, amount);
        } catch (InsufficientBalanceException e) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("code", "INSUFFICIENT_BALANCE");
            out.put("balance", e.getBalance());
            out.put("message", "余额不足，无法合庄");
            return ResponseEntity.status(422).body(out);
        } catch (RuntimeException e) {
            return PlayerBetController.err(422, "CO_BANK_FAILED", e.getMessage());
        }
        return okRound(round);
    }

    @PostMapping({"/co-bank/remove", "/rounds/current/co-bank/remove"})
    public ResponseEntity<Map<String, Object>> removeCoBankCurrent(@RequestBody(required = false) Map<String, Object> body) {
        return removeCoBank(body, null);
    }

    @DeleteMapping("/rounds/current/co-bank")
    public ResponseEntity<Map<String, Object>> removeCoBankCurrentDelete(@RequestBody(required = false) Map<String, Object> body) {
        return removeCoBank(body, null);
    }

    @PostMapping("/rounds/{id}/co-bank/remove")
    public ResponseEntity<Map<String, Object>> removeCoBankByIdPost(@PathVariable("id") String id,
                                                                    @RequestBody(required = false) Map<String, Object> body) {
        return removeCoBank(body, id);
    }

    @DeleteMapping("/rounds/{id}/co-bank")
    public ResponseEntity<Map<String, Object>> removeCoBankByIdDelete(@PathVariable("id") String id,
                                                                      @RequestBody(required = false) Map<String, Object> body) {
        return removeCoBank(body, id);
    }

    private ResponseEntity<Map<String, Object>> removeCoBank(Map<String, Object> body, String id) {
        SangongRound round = resolveRound(id);
        if (round == null) {
            return PlayerBetController.err(404, "NOT_FOUND", "局不存在");
        }
        long userId = Req.lng(body, "userId", 0);
        if (userId <= 0) {
            return PlayerBetController.err(422, "INVALID_REQUEST", "缺少 userId");
        }
        Map<String, Object> removed;
        try {
            removed = rounds.removeCoBank(round, userId);
            round = rounds.findById(round.getId());
        } catch (RuntimeException e) {
            return PlayerBetController.err(422, "CO_BANK_FAILED", e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("removed", removed);
        out.put("round", round != null ? rounds.formatRound(round) : null);
        return ResponseEntity.ok(out);
    }

    @PostMapping("/rounds/{id}/co-bank/close")
    public ResponseEntity<Map<String, Object>> closeCoBank(@PathVariable long id) {
        SangongRound round = rounds.findById(id);
        if (round == null) {
            return PlayerBetController.err(404, "NOT_FOUND", "局不存在");
        }
        try {
            round = rounds.closeCoBank(round);
        } catch (RuntimeException e) {
            return PlayerBetController.err(422, "CO_BANK_FAILED", e.getMessage());
        }
        return okRound(round);
    }

    // ===== 下注窗口提交/预览 =====

    @PostMapping({"/betting/submit", "/rounds/current/betting/submit"})
    public ResponseEntity<Map<String, Object>> submitCurrent(@RequestBody(required = false) Map<String, Object> body) {
        return submitBetWindow(body, null);
    }

    @PostMapping("/rounds/{id}/betting/submit")
    public ResponseEntity<Map<String, Object>> submitById(@PathVariable("id") String id,
                                                          @RequestBody(required = false) Map<String, Object> body) {
        return submitBetWindow(body, id);
    }

    private ResponseEntity<Map<String, Object>> submitBetWindow(Map<String, Object> body, String id) {
        SangongRound round = resolveRound(id);
        if (round == null) {
            return PlayerBetController.err(404, "NOT_FOUND", "当前无可提交局");
        }
        Map<String, Object> result;
        try {
            result = rounds.submitBetWindow(round,
                Req.lngOrNull(body, "untilMessageId"),
                Req.lngOrNull(body, "untilMsgSeq"),
                parseExcludeMessageIds(body));
        } catch (RuntimeException e) {
            return PlayerBetController.err(422, "SUBMIT_FAILED", e.getMessage());
        }
        SangongRound fresh = rounds.findById(round.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("submit", result);
        out.put("round", rounds.formatRound(fresh != null ? fresh : round));
        return ResponseEntity.ok(out);
    }

    @PostMapping({"/betting/preview", "/rounds/current/betting/preview"})
    public ResponseEntity<Map<String, Object>> previewCurrent(@RequestBody(required = false) Map<String, Object> body) {
        return previewBetWindow(body, null);
    }

    @PostMapping("/rounds/{id}/betting/preview")
    public ResponseEntity<Map<String, Object>> previewById(@PathVariable("id") String id,
                                                           @RequestBody(required = false) Map<String, Object> body) {
        return previewBetWindow(body, id);
    }

    private ResponseEntity<Map<String, Object>> previewBetWindow(Map<String, Object> body, String id) {
        SangongRound round = resolveRound(id);
        if (round == null) {
            return PlayerBetController.err(404, "NOT_FOUND", "当前无可预览局");
        }
        Map<String, Object> preview;
        try {
            preview = rounds.previewBetWindow(round,
                Req.lngOrNull(body, "untilMessageId"),
                Req.lngOrNull(body, "untilMsgSeq"),
                parseExcludeMessageIds(body));
        } catch (RuntimeException e) {
            return PlayerBetController.err(422, "PREVIEW_FAILED", e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("preview", preview);
        out.put("round", rounds.formatRound(round));
        return ResponseEntity.ok(out);
    }

    // ===== 开彩 =====

    @GetMapping("/rounds/current/draws")
    public ResponseEntity<Map<String, Object>> showDrawsCurrent() {
        return showDraws(null);
    }

    @GetMapping("/rounds/{id}/draws")
    public ResponseEntity<Map<String, Object>> showDrawsById(@PathVariable("id") String id) {
        return showDraws(id);
    }

    private ResponseEntity<Map<String, Object>> showDraws(String id) {
        SangongRound round = resolveRound(id);
        if (round == null) {
            return PlayerBetController.err(404, "NOT_FOUND", "局不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("draw", draws.getStatus(round));
        out.put("round", rounds.formatRound(round));
        return ResponseEntity.ok(out);
    }

    @PostMapping({"/draws", "/rounds/current/draws"})
    public ResponseEntity<Map<String, Object>> recordDrawsCurrent(@RequestBody(required = false) Map<String, Object> body) {
        return recordDraws(body, null);
    }

    @PostMapping("/rounds/{id}/draws")
    public ResponseEntity<Map<String, Object>> recordDrawsById(@PathVariable("id") String id,
                                                               @RequestBody(required = false) Map<String, Object> body) {
        return recordDraws(body, id);
    }

    private ResponseEntity<Map<String, Object>> recordDraws(Map<String, Object> body, String id) {
        SangongRound round = resolveRound(id);
        if (round == null) {
            return PlayerBetController.err(404, "NOT_FOUND", "局不存在");
        }
        Map<Integer, String> payload = parseDrawPayload(body);
        if (payload.isEmpty()) {
            return PlayerBetController.err(422, "INVALID_REQUEST", "请提供 door+amount 或 draws 数组");
        }
        Map<String, Object> status;
        try {
            status = draws.recordDraws(round, payload);
        } catch (RuntimeException e) {
            return PlayerBetController.err(422, "DRAW_FAILED", e.getMessage());
        }
        SangongRound fresh = rounds.findById(round.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("draw", status);
        out.put("round", rounds.formatRound(fresh != null ? fresh : round));
        return ResponseEntity.ok(out);
    }

    // ===== 结算/冲正 =====

    @PostMapping("/rounds/{id}/settle")
    public ResponseEntity<Map<String, Object>> settle(@PathVariable long id) {
        SangongRound round = rounds.findById(id);
        if (round == null) {
            return PlayerBetController.err(404, "NOT_FOUND", "局不存在");
        }
        Map<String, Object> settlement;
        try {
            settlement = settle.settle(round);
        } catch (RuntimeException e) {
            return PlayerBetController.err(422, "SETTLE_FAILED", e.getMessage());
        }
        SangongRound fresh = rounds.findById(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("settlement", settlement);
        out.put("round", fresh != null ? rounds.formatRound(fresh) : null);
        return ResponseEntity.ok(out);
    }

    /** 冲正本局结算（下一局未开时）：回滚账务并解锁，可改开彩后再 settle。 */
    @PostMapping("/rounds/{id}/void-settlement")
    public ResponseEntity<Map<String, Object>> voidSettlement(@PathVariable long id) {
        SangongRound round = rounds.findById(id);
        if (round == null) {
            return PlayerBetController.err(404, "NOT_FOUND", "局不存在");
        }
        Map<String, Object> voided;
        try {
            voided = settle.voidSettlement(round);
        } catch (RuntimeException e) {
            return PlayerBetController.err(422, "VOID_SETTLE_FAILED", e.getMessage());
        }
        SangongRound fresh = rounds.findById(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("voided", voided);
        out.put("round", fresh != null ? rounds.formatRound(fresh) : null);
        return ResponseEntity.ok(out);
    }

    /** 冲正重结：先冲正并清空开彩，必须带新开奖号码后再结算。 */
    @PostMapping("/rounds/{id}/resettle")
    public ResponseEntity<Map<String, Object>> resettle(@PathVariable long id,
                                                        @RequestBody(required = false) Map<String, Object> body) {
        SangongRound round = rounds.findById(id);
        if (round == null) {
            return PlayerBetController.err(404, "NOT_FOUND", "局不存在");
        }
        Map<Integer, String> drawInputs = parseDrawPayload(body);
        if (drawInputs.isEmpty()) {
            return PlayerBetController.err(422, "RESETTLE_FAILED", "冲正重结须重新录入开奖号码");
        }
        Map<String, Object> result;
        try {
            result = settle.resettle(round, drawInputs);
        } catch (RuntimeException e) {
            return PlayerBetController.err(422, "RESETTLE_FAILED", e.getMessage());
        }
        SangongRound fresh = rounds.findById(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("voided", result.get("voided"));
        out.put("settlement", result.get("settlement"));
        out.put("round", fresh != null ? rounds.formatRound(fresh) : null);
        return ResponseEntity.ok(out);
    }

    // ===== 内部 =====

    private ResponseEntity<Map<String, Object>> okRound(SangongRound round) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("round", rounds.formatRound(round));
        return ResponseEntity.ok(out);
    }

    private SangongRound resolveRound(String id) {
        if (id == null || "current".equals(id)) {
            return rounds.getCurrent();
        }
        try {
            return rounds.findById(Long.parseLong(id));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 已结算局回退到当前局（与 PHP setupBanker 语义一致）。 */
    private SangongRound resolveSetupRound(String id) {
        if (id == null || "current".equals(id)) {
            return rounds.getCurrent();
        }
        SangongRound round;
        try {
            round = rounds.findById(Long.parseLong(id));
        } catch (NumberFormatException e) {
            return null;
        }
        if (round != null && SangongRound.SETTLED.equals(round.getStatus())) {
            SangongRound current = rounds.getCurrent();
            if (current != null && current.getId() != round.getId()) {
                round = current;
            }
        }
        return round;
    }

    private long resolveBankerLimitInput(Map<String, Object> body) {
        if (Req.has(body, "limit")) {
            return Req.lng(body, "limit", 0);
        }
        if (Req.has(body, "amount")) {
            return Req.lng(body, "amount", 0);
        }
        return 0;
    }

    private Integer resolveDoorCountInput(Map<String, Object> body) {
        if (Req.has(body, "doorCount")) {
            return Req.intval(body, "doorCount", 0);
        }
        if (Req.has(body, "door")) {
            return Req.intval(body, "door", 0);
        }
        return null;
    }

    private ResponseEntity<Map<String, Object>> saveDoorCount(int doorCount) {
        Map<String, Object> settings;
        try {
            settings = gameSettings.update(Map.of("doorCount", doorCount));
        } catch (RuntimeException e) {
            return PlayerBetController.err(422, "INVALID_SETTINGS", e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("action", "door_count");
        out.put("message", "门数已保存，建议下一局生效");
        out.put("doorCount", settings.get("doorCount"));
        out.put("settings", settings);
        return ResponseEntity.ok(out);
    }

    private Map<Integer, String> parseDrawPayload(Map<String, Object> body) {
        Map<Integer, String> out = new LinkedHashMap<>();
        Object drawsObj = Req.body(body).get("draws");
        if (drawsObj instanceof List<?> items) {
            for (Object o : items) {
                if (!(o instanceof Map<?, ?> item)) continue;
                Object doorObj = item.get("door");
                Object amountObj = item.get("amount");
                if (doorObj == null || amountObj == null) continue;
                try {
                    out.put((int) Double.parseDouble(String.valueOf(doorObj)), String.valueOf(amountObj));
                } catch (NumberFormatException ignored) {
                }
            }
            return out;
        }
        if (Req.has(body, "door") && Req.has(body, "amount")) {
            out.put(Req.intval(body, "door", 0), Req.str(body, "amount", ""));
        }
        return out;
    }

    private List<Long> parseExcludeMessageIds(Map<String, Object> body) {
        Object single = Req.has(body, "excludeMessageId") ? Req.body(body).get("excludeMessageId") : null;
        Object many = Req.has(body, "excludeMessageIds") ? Req.body(body).get("excludeMessageIds") : null;
        return messageOrder.normalizeExcludeMessageIds(single, many);
    }
}
