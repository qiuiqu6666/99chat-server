package com.chat99.sangong.service;

import com.chat99.sangong.domain.SangongCoBank;
import com.chat99.sangong.domain.SangongRound;
import com.chat99.sangong.domain.SangongUser;
import com.chat99.sangong.repository.CoBankRepository;
import com.chat99.sangong.repository.RoundRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 合庄占股汇总（原庄家出资 banker_limit + 追加合庄）。 */
@Service
public class CoBankService {
    private final CoBankRepository coBanks;
    private final RoundRepository rounds;
    private final UserService users;

    public CoBankService(CoBankRepository coBanks, RoundRepository rounds, UserService users) {
        this.coBanks = coBanks;
        this.rounds = rounds;
        this.users = users;
    }

    public Map<String, Object> getCoBankSummary(long roundId) {
        SangongRound round = rounds.findById(roundId).orElse(null);
        List<SangongCoBank> entries = coBanks.listByRound(roundId);
        List<Map<String, Object>> members = new ArrayList<>();

        if (round != null && round.getBankerUserId() != null
            && round.getBankerLimit() != null && round.getBankerLimit() > 0) {
            SangongUser banker = users.findById(round.getBankerUserId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", round.getBankerUserId());
            m.put("nickname", banker != null ? banker.getNickname() : "");
            m.put("amount", round.getBankerLimit());
            m.put("isMainBanker", true);
            members.add(m);
        }

        int coBankerCount = 0;
        for (SangongCoBank entry : entries) {
            if (round != null && round.getBankerUserId() != null
                && entry.getUserId() == round.getBankerUserId()) {
                continue;
            }
            SangongUser user = users.findById(entry.getUserId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", entry.getUserId());
            m.put("nickname", user != null ? user.getNickname() : "");
            m.put("amount", entry.getAmount());
            m.put("isMainBanker", false);
            members.add(m);
            coBankerCount++;
        }
        return summarize(members, coBankerCount);
    }

    private Map<String, Object> summarize(List<Map<String, Object>> members, int coBankerCount) {
        long pool = members.stream().mapToLong(m -> ((Number) m.get("amount")).longValue()).sum();
        for (Map<String, Object> member : members) {
            long amount = ((Number) member.get("amount")).longValue();
            double sharePercent = pool > 0 ? Math.round(amount * 10000.0 / pool) / 100.0 : 0.0;
            member.put("sharePercent", sharePercent);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("poolTotal", pool);
        out.put("count", members.size());
        out.put("coBankerCount", coBankerCount);
        out.put("members", members);
        return out;
    }
}
