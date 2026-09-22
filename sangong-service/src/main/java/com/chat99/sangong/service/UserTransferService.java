package com.chat99.sangong.service;

import com.chat99.sangong.common.InsufficientBalanceException;
import com.chat99.sangong.domain.SangongUser;
import com.chat99.sangong.repository.UserRepository;
import com.chat99.sangong.tenant.TenantContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Transactional;

/** 统一用户资金划转：代理和玩家使用同一余额模型，只允许向下级划转。 */
@Service
public class UserTransferService {
    private final NamedParameterJdbcTemplate jdbc;
    private final UserRepository users;
    private final BalanceService balance;
    private final SessionService sessions;
    private final ImService im;
    private final MainUserProfileService profiles;
    public UserTransferService(NamedParameterJdbcTemplate jdbc, UserRepository users, BalanceService balance,
                               SessionService sessions, @Lazy ImService im, MainUserProfileService profiles) {
        this.jdbc = jdbc; this.users = users; this.balance = balance; this.sessions = sessions;
        this.im = im; this.profiles = profiles;
    }

    @Transactional
    public Map<String, Object> transferToChild(long fromId, long toId, long amount, String note) {
        if (amount <= 0 || fromId == toId) throw new IllegalArgumentException("划转参数无效");
        String tenant = TenantContext.require();
        Integer allowed = jdbc.queryForObject("SELECT COUNT(*) FROM sangong_user_hierarchy " +
            "WHERE tenant_id=:t AND user_id=:to AND path LIKE CONCAT('%/',:from,'/%') AND is_active=1",
            new MapSqlParameterSource().addValue("t", tenant).addValue("from", fromId).addValue("to", toId), Integer.class);
        if (allowed == null || allowed == 0) throw new IllegalStateException("只能向自己的下级划转");
        SangongUser from = users.lockById(fromId);
        SangongUser to = users.lockById(toId);
        if (from == null || to == null) throw new IllegalArgumentException("用户不存在");
        if (from.getBalance() < amount) throw new InsufficientBalanceException(from.getBalance());
        String ref = UUID.randomUUID().toString();
        var running = sessions.getRunning();
        Long sessionId = running == null ? null : running.getId();
        String transferNote = note == null ? "" : note;
        BalanceService.BalanceResult fromResult = balance.debit(from, amount, "user_transfer_out", sessionId,
            transferNote.isBlank() ? "向下级划转" : transferNote, "transfer", null, from.getImUserId());
        BalanceService.BalanceResult toResult = balance.credit(to, amount, "user_transfer_in", sessionId,
            transferNote.isBlank() ? "下级收款" : transferNote, "transfer", null, from.getImUserId());
        jdbc.update("INSERT INTO sangong_user_transfers(tenant_id,session_id,from_user_id,to_user_id,amount,transfer_type,reference_id) " +
            "VALUES(:t,:s,:from,:to,:amount,'AGENT_TO_CHILD',:ref)", new MapSqlParameterSource()
            .addValue("t", tenant).addValue("from", fromId).addValue("to", toId)
            .addValue("s", sessionId).addValue("amount", amount).addValue("ref", ref));
        String operatorDisplay = profiles.getNickname(from.getImUserId());
        if (operatorDisplay == null || operatorDisplay.isBlank()) operatorDisplay = from.getImUserId();
        else operatorDisplay = operatorDisplay.trim() + "(" + from.getImUserId() + ")";
        im.notifyAdminLedgerAdjust("debit", displayName(from), amount, fromResult.user().getBalance(),
            operatorDisplay, transferNote, fromResult.ledger().getId());
        im.notifyAdminLedgerAdjust("credit", displayName(to), amount, toResult.user().getBalance(),
            operatorDisplay, transferNote, toResult.ledger().getId());
        Map<String, Object> out = new LinkedHashMap<>(); out.put("ok", true); out.put("referenceId", ref);
        out.put("fromImUserId", from.getImUserId());
        out.put("toImUserId", to.getImUserId());
        out.put("amount", amount); return out;
    }

    private static String displayName(SangongUser user) {
        return user.getNickname() == null || user.getNickname().isBlank() ? user.getImUserId() : user.getNickname();
    }
}
