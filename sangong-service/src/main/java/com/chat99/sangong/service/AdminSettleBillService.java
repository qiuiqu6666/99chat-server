package com.chat99.sangong.service;

import com.chat99.sangong.config.SangongProperties;
import com.chat99.sangong.domain.SangongRound;
import com.chat99.sangong.domain.SangongSession;
import com.chat99.sangong.domain.SangongUser;
import com.chat99.sangong.repository.LedgerRepository;
import com.chat99.sangong.repository.RoundRepository;
import com.chat99.sangong.repository.SessionRepository;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/** 报表群结算账单数据（与 PHP AdminSettleBillService 对齐）。 */
@Service
public class AdminSettleBillService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");
    private static final double PACKAGE_FEE = 3.0;
    private static final double RAKE_RATE = 0.06;

    private final SettleService settle;
    private final UserService users;
    private final UserGroupService groups;
    private final RoundRepository rounds;
    private final SessionRepository sessions;
    private final LedgerRepository ledgers;
    private final SangongProperties props;

    public AdminSettleBillService(@Lazy SettleService settle, UserService users, UserGroupService groups,
                                  RoundRepository rounds, SessionRepository sessions,
                                  LedgerRepository ledgers, SangongProperties props) {
        this.settle = settle;
        this.users = users;
        this.groups = groups;
        this.rounds = rounds;
        this.sessions = sessions;
        this.ledgers = ledgers;
        this.props = props;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> build(SangongRound currentRound) {
        if (!SangongRound.SETTLED.equals(currentRound.getStatus())) {
            throw new RuntimeException("当前局须已结算");
        }
        SangongSession session = sessions.findById(currentRound.getSessionId()).orElse(null);
        if (session == null) {
            throw new RuntimeException("会话不存在");
        }

        List<SangongRound> settledRounds = rounds.listSettledBySession(session.getId());
        long sessionBetTotal = 0;
        long currentRoundBet = 0;
        Map<Long, Map<String, Object>> players = new LinkedHashMap<>();
        Map<Long, Map<String, Object>> bankers = new LinkedHashMap<>();

        for (SangongRound round : settledRounds) {
            Map<String, Object> report = settle.buildReportForRound(round);
            long roundBet = ((Number) report.getOrDefault("grandTotal", 0)).longValue();
            long roundRake = calcRake(roundBet);
            sessionBetTotal += roundBet;
            if (round.getId() == currentRound.getId()) {
                currentRoundBet = roundBet;
            }
            for (Object o : (List<?>) report.getOrDefault("players", List.of())) {
                Map<String, Object> player = (Map<String, Object>) o;
                long userId = ((Number) player.getOrDefault("userId", 0)).longValue();
                if (userId <= 0) continue;
                touch(players, userId);
                markRound(players, userId, round.getId());
                add(players.get(userId), "betAmount", ((Number) player.getOrDefault("totalBet", 0)).longValue());
                add(players.get(userId), "net", ((Number) player.getOrDefault("net", 0)).longValue());
            }
            for (Object o : (List<?>) report.getOrDefault("bankers", List.of())) {
                Map<String, Object> banker = (Map<String, Object>) o;
                long userId = ((Number) banker.getOrDefault("userId", 0)).longValue();
                if (userId <= 0) continue;
                touch(bankers, userId);
                markRound(bankers, userId, round.getId());
                add(bankers.get(userId), "betAmount", roundBet);
                add(bankers.get(userId), "rebate", roundRake);
                add(bankers.get(userId), "net", ((Number) banker.getOrDefault("net", 0)).longValue());
            }
        }

        Map<Long, Map<String, Object>> ledgerPeople = new LinkedHashMap<>();
        applyLedger(ledgerPeople, session);
        applyLedger(players, session);
        hydrate(players);
        hydrate(bankers);
        hydrate(ledgerPeople);

        int roundCount = settledRounds.size();
        String settledAt = currentRound.getSettledAt() == null ? ""
            : DT.format(currentRound.getSettledAt().atZone(ZONE));
        String title = props.getImage().getBetReportTitle();
        if (title == null || title.isBlank()) title = "三公";

        Map<String, Object> current = new LinkedHashMap<>();
        current.put("time", settledAt);
        current.put("roundCount", roundCount);
        current.put("betAmount", currentRoundBet);
        current.put("packageFee", formatMoney(PACKAGE_FEE));
        current.put("rake", calcRake(currentRoundBet));

        Map<String, Object> sessionTotal = new LinkedHashMap<>();
        sessionTotal.put("roundCount", roundCount);
        sessionTotal.put("betAmount", sessionBetTotal);
        sessionTotal.put("packageFee", formatMoney(PACKAGE_FEE * roundCount));
        sessionTotal.put("rake", calcRake(sessionBetTotal));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", title);
        out.put("periodNo", currentRound.getPeriodNo());
        out.put("currentRound", current);
        out.put("sessionTotal", sessionTotal);
        out.put("personalGroups", buildPersonalGroups(players));
        out.put("bankerGroups", buildBankerGroups(bankers));
        out.put("ledgerGroups", buildLedgerGroups(ledgerPeople));
        return out;
    }

    private void applyLedger(Map<Long, Map<String, Object>> people, SangongSession session) {
        Map<Long, Map<String, Long>> sums = ledgers.sumCreditDebitForSession(
            session.getId(), session.getStartedAt(), session.getStoppedAt());
        for (Map.Entry<Long, Map<String, Long>> e : sums.entrySet()) {
            touch(people, e.getKey());
            people.get(e.getKey()).put("creditTotal", e.getValue().getOrDefault("creditTotal", 0L));
            people.get(e.getKey()).put("debitTotal", e.getValue().getOrDefault("debitTotal", 0L));
        }
    }

    private void hydrate(Map<Long, Map<String, Object>> people) {
        for (Map.Entry<Long, Map<String, Object>> e : people.entrySet()) {
            SangongUser user = users.findById(e.getKey());
            if (user == null) continue;
            Map<String, Object> person = e.getValue();
            person.put("imUserId", user.getImUserId());
            person.put("nickname", users.resolveNickname(user));
            person.put("balanceAfter", user.getBalance());
            person.put("groupKey", resolveGroupKey(user));
        }
    }

    private String resolveGroupKey(SangongUser user) {
        if (user.getGroupId() == null) return "0";
        Map<String, Object> group = groups.findById(user.getGroupId());
        if (group == null) return String.valueOf(user.getGroupId());
        Object code = group.get("code");
        if (code != null && !String.valueOf(code).isBlank()) {
            return String.valueOf(code).trim();
        }
        return String.valueOf(group.getOrDefault("id", user.getGroupId()));
    }

    private List<Map<String, Object>> buildPersonalGroups(Map<Long, Map<String, Object>> people) {
        Map<String, Map<String, Object>> buckets = new LinkedHashMap<>();
        for (Map.Entry<Long, Map<String, Object>> e : people.entrySet()) {
            Map<String, Object> person = e.getValue();
            if (!playerHasActivity(person)) continue;
            String groupKey = String.valueOf(person.getOrDefault("groupKey", "0"));
            Map<String, Object> bucket = buckets.computeIfAbsent(groupKey, k -> {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("groupKey", k);
                b.put("players", new ArrayList<Map<String, Object>>());
                b.put("totals", emptyPersonalTotals());
                return b;
            });
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", e.getKey());
            row.put("imUserId", person.get("imUserId"));
            row.put("nickname", String.valueOf(person.getOrDefault("nickname", "")));
            row.put("roundCount", ((Number) person.getOrDefault("roundCount", 0)).intValue());
            row.put("betAmount", ((Number) person.getOrDefault("betAmount", 0)).longValue());
            row.put("rebate", 0);
            row.put("creditTotal", ((Number) person.getOrDefault("creditTotal", 0)).longValue());
            row.put("debitTotal", ((Number) person.getOrDefault("debitTotal", 0)).longValue());
            row.put("net", ((Number) person.getOrDefault("net", 0)).longValue());
            row.put("balanceAfter", person.get("balanceAfter"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = (List<Map<String, Object>>) bucket.get("players");
            list.add(row);
            @SuppressWarnings("unchecked")
            Map<String, Number> totals = (Map<String, Number>) bucket.get("totals");
            addPersonalTotals(totals, row);
        }
        return sortBuckets(buckets, Comparator
            .comparing((Map<String, Object> a) -> -((Number) a.getOrDefault("net", 0)).longValue())
            .thenComparing(a -> String.valueOf(a.getOrDefault("nickname", ""))));
    }

    private List<Map<String, Object>> buildBankerGroups(Map<Long, Map<String, Object>> bankers) {
        Map<String, Map<String, Object>> buckets = new LinkedHashMap<>();
        for (Map.Entry<Long, Map<String, Object>> e : bankers.entrySet()) {
            Map<String, Object> person = e.getValue();
            if (((Number) person.getOrDefault("roundCount", 0)).intValue() <= 0) continue;
            String groupKey = String.valueOf(person.getOrDefault("groupKey", "0"));
            Map<String, Object> bucket = buckets.computeIfAbsent(groupKey, k -> {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("groupKey", k);
                b.put("players", new ArrayList<Map<String, Object>>());
                b.put("totals", emptyBankerTotals());
                return b;
            });
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", e.getKey());
            row.put("imUserId", person.get("imUserId"));
            row.put("nickname", String.valueOf(person.getOrDefault("nickname", "")));
            row.put("roundCount", ((Number) person.getOrDefault("roundCount", 0)).intValue());
            row.put("betAmount", ((Number) person.getOrDefault("betAmount", 0)).longValue());
            row.put("rebate", ((Number) person.getOrDefault("rebate", 0)).longValue());
            row.put("net", ((Number) person.getOrDefault("net", 0)).longValue());
            row.put("balanceAfter", person.get("balanceAfter"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = (List<Map<String, Object>>) bucket.get("players");
            list.add(row);
            @SuppressWarnings("unchecked")
            Map<String, Number> totals = (Map<String, Number>) bucket.get("totals");
            addBankerTotals(totals, row);
        }
        return sortBuckets(buckets, Comparator
            .comparing((Map<String, Object> a) -> -((Number) a.getOrDefault("net", 0)).longValue())
            .thenComparing(a -> String.valueOf(a.getOrDefault("nickname", ""))));
    }

    private List<Map<String, Object>> buildLedgerGroups(Map<Long, Map<String, Object>> people) {
        Map<String, Map<String, Object>> buckets = new LinkedHashMap<>();
        for (Map.Entry<Long, Map<String, Object>> e : people.entrySet()) {
            Map<String, Object> person = e.getValue();
            long credit = ((Number) person.getOrDefault("creditTotal", 0)).longValue();
            long debit = ((Number) person.getOrDefault("debitTotal", 0)).longValue();
            if (credit <= 0 && debit <= 0) continue;
            String groupKey = String.valueOf(person.getOrDefault("groupKey", "0"));
            Map<String, Object> bucket = buckets.computeIfAbsent(groupKey, k -> {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("groupKey", k);
                b.put("players", new ArrayList<Map<String, Object>>());
                b.put("totals", emptyLedgerTotals());
                return b;
            });
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", e.getKey());
            row.put("imUserId", person.get("imUserId"));
            row.put("nickname", String.valueOf(person.getOrDefault("nickname", "")));
            row.put("creditTotal", credit);
            row.put("debitTotal", debit);
            row.put("net", credit - debit);
            row.put("balanceAfter", person.get("balanceAfter"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = (List<Map<String, Object>>) bucket.get("players");
            list.add(row);
            @SuppressWarnings("unchecked")
            Map<String, Number> totals = (Map<String, Number>) bucket.get("totals");
            addLedgerTotals(totals, row);
        }
        return sortBuckets(buckets, Comparator
            .comparing((Map<String, Object> a) -> -((Number) a.getOrDefault("creditTotal", 0)).longValue())
            .thenComparing(a -> String.valueOf(a.getOrDefault("nickname", ""))));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sortBuckets(Map<String, Map<String, Object>> buckets,
                                                  Comparator<Map<String, Object>> playerCmp) {
        List<String> keys = new ArrayList<>(buckets.keySet());
        keys.sort((a, b) -> {
            if ("0".equals(a)) return -1;
            if ("0".equals(b)) return 1;
            return a.compareTo(b);
        });
        List<Map<String, Object>> out = new ArrayList<>();
        for (String key : keys) {
            Map<String, Object> bucket = buckets.get(key);
            List<Map<String, Object>> players = (List<Map<String, Object>>) bucket.get("players");
            players.sort(playerCmp);
            out.add(bucket);
        }
        return out;
    }

    private static boolean playerHasActivity(Map<String, Object> person) {
        return ((Number) person.getOrDefault("roundCount", 0)).intValue() > 0
            || ((Number) person.getOrDefault("betAmount", 0)).longValue() > 0
            || ((Number) person.getOrDefault("net", 0)).longValue() != 0;
    }

    private static void touch(Map<Long, Map<String, Object>> people, long userId) {
        people.computeIfAbsent(userId, id -> {
            Map<String, Object> m = new HashMap<>();
            m.put("roundCount", 0);
            m.put("roundIds", new HashSet<Long>());
            m.put("betAmount", 0L);
            m.put("rebate", 0L);
            m.put("creditTotal", 0L);
            m.put("debitTotal", 0L);
            m.put("net", 0L);
            return m;
        });
    }

    @SuppressWarnings("unchecked")
    private static void markRound(Map<Long, Map<String, Object>> people, long userId, long roundId) {
        Map<String, Object> person = people.get(userId);
        Set<Long> ids = (Set<Long>) person.get("roundIds");
        if (ids.add(roundId)) {
            person.put("roundCount", ((Number) person.get("roundCount")).intValue() + 1);
        }
    }

    private static void add(Map<String, Object> person, String key, long delta) {
        person.put(key, ((Number) person.getOrDefault(key, 0L)).longValue() + delta);
    }

    private static Map<String, Number> emptyPersonalTotals() {
        Map<String, Number> m = new LinkedHashMap<>();
        m.put("roundCount", 0); m.put("betAmount", 0L); m.put("rebate", 0);
        m.put("creditTotal", 0L); m.put("debitTotal", 0L); m.put("net", 0L); m.put("balanceAfter", 0L);
        return m;
    }

    private static Map<String, Number> emptyBankerTotals() {
        Map<String, Number> m = new LinkedHashMap<>();
        m.put("roundCount", 0); m.put("betAmount", 0L); m.put("rebate", 0L);
        m.put("net", 0L); m.put("balanceAfter", 0L);
        return m;
    }

    private static Map<String, Number> emptyLedgerTotals() {
        Map<String, Number> m = new LinkedHashMap<>();
        m.put("creditTotal", 0L); m.put("debitTotal", 0L); m.put("net", 0L); m.put("balanceAfter", 0L);
        return m;
    }

    private static void addPersonalTotals(Map<String, Number> totals, Map<String, Object> row) {
        totals.put("roundCount", totals.get("roundCount").intValue() + ((Number) row.get("roundCount")).intValue());
        totals.put("betAmount", totals.get("betAmount").longValue() + ((Number) row.get("betAmount")).longValue());
        totals.put("creditTotal", totals.get("creditTotal").longValue() + ((Number) row.get("creditTotal")).longValue());
        totals.put("debitTotal", totals.get("debitTotal").longValue() + ((Number) row.get("debitTotal")).longValue());
        totals.put("net", totals.get("net").longValue() + ((Number) row.get("net")).longValue());
        if (row.get("balanceAfter") != null) {
            totals.put("balanceAfter", totals.get("balanceAfter").longValue() + ((Number) row.get("balanceAfter")).longValue());
        }
    }

    private static void addBankerTotals(Map<String, Number> totals, Map<String, Object> row) {
        totals.put("roundCount", totals.get("roundCount").intValue() + ((Number) row.get("roundCount")).intValue());
        totals.put("betAmount", totals.get("betAmount").longValue() + ((Number) row.get("betAmount")).longValue());
        totals.put("rebate", totals.get("rebate").longValue() + ((Number) row.get("rebate")).longValue());
        totals.put("net", totals.get("net").longValue() + ((Number) row.get("net")).longValue());
        if (row.get("balanceAfter") != null) {
            totals.put("balanceAfter", totals.get("balanceAfter").longValue() + ((Number) row.get("balanceAfter")).longValue());
        }
    }

    private static void addLedgerTotals(Map<String, Number> totals, Map<String, Object> row) {
        totals.put("creditTotal", totals.get("creditTotal").longValue() + ((Number) row.get("creditTotal")).longValue());
        totals.put("debitTotal", totals.get("debitTotal").longValue() + ((Number) row.get("debitTotal")).longValue());
        totals.put("net", totals.get("net").longValue() + ((Number) row.get("net")).longValue());
        if (row.get("balanceAfter") != null) {
            totals.put("balanceAfter", totals.get("balanceAfter").longValue() + ((Number) row.get("balanceAfter")).longValue());
        }
    }

    private static long calcRake(long betAmount) {
        return Math.round(betAmount * RAKE_RATE);
    }

    private static String formatMoney(double amount) {
        return String.format("%.2f", amount);
    }
}
