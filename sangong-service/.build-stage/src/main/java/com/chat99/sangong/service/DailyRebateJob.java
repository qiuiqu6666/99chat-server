package com.chat99.sangong.service;

import com.chat99.sangong.domain.SangongUser;
import com.chat99.sangong.repository.UserRepository;
import com.chat99.sangong.tenant.TenantContext;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每日自动返水；手动领取过的账户只领取剩余增量。 */
public class DailyRebateJob {
    private static final Logger log = LoggerFactory.getLogger(DailyRebateJob.class);
    private final NamedParameterJdbcTemplate jdbc;
    private final UserRepository users;
    private final RebateClaimService claims;

    public DailyRebateJob(NamedParameterJdbcTemplate jdbc, UserRepository users, RebateClaimService claims) {
        this.jdbc = jdbc; this.users = users; this.claims = claims;
    }

    public void run() {
        List<String> tenants = jdbc.getJdbcTemplate().queryForList(
            "SELECT tenant_id FROM sangong_tenants WHERE is_active=1", String.class);
        for (String tenant : tenants) {
            try {
                TenantContext.run(tenant, () -> {
                    for (SangongUser user : users.listAll()) {
                        try { claims.claimPlayerRebate(user); }
                        catch (Exception e) { log.warn("daily rebate failed tenant={} user={}: {}", tenant, user.getId(), e.getMessage()); }
                    }
                });
            } catch (Exception e) {
                log.error("daily rebate tenant failed tenant={}: {}", tenant, e.getMessage(), e);
            }
        }
    }
}
