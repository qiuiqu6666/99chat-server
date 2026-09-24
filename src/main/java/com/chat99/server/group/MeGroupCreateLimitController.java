package com.chat99.server.group;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeGroupCreateLimitController {

    private final GroupJoinLimitService joinLimitService;
    private final GroupCreateLimitConfigService config;

    public MeGroupCreateLimitController(GroupJoinLimitService joinLimitService,
                                        GroupCreateLimitConfigService config) {
        this.joinLimitService = joinLimitService;
        this.config = config;
    }

    public record GroupQuotaView(String groupType, int max, int used, int remaining, boolean limited) {}

    public record JoinQuotaView(int max, int used, int remaining, boolean limited) {}

    public record CommunityCreatePriceView(String currency, long amountMinor) {}

    public record GroupCreateLimitsResponse(
        boolean enabled,
        JoinQuotaView joinGroups,
        JoinQuotaView communityJoinGroups,
        GroupQuotaView communityGroups,
        CommunityCreatePriceView communityCreatePrice) {}

    @GetMapping("/me/group-create-limits")
    public GroupCreateLimitsResponse limits(Authentication auth) {
        String userId = (String) auth.getPrincipal();
        GroupJoinLimitService.JoinQuota join = joinLimitService.nonCommunityJoinQuota(userId);
        GroupJoinLimitService.JoinQuota communityJoin = joinLimitService.communityJoinQuota(userId);
        GroupJoinLimitService.JoinQuota communityCreate = joinLimitService.communityCreateQuota(userId);
        return new GroupCreateLimitsResponse(
            config.isEnabled(),
            new JoinQuotaView(join.max(), join.used(), join.remaining(), join.limited()),
            new JoinQuotaView(
                communityJoin.max(), communityJoin.used(), communityJoin.remaining(), communityJoin.limited()),
            new GroupQuotaView(
                "Community",
                communityCreate.max(),
                communityCreate.used(),
                communityCreate.remaining(),
                communityCreate.limited()),
            new CommunityCreatePriceView(config.getCommunityPriceCurrency().getApiCode(),
                config.getCommunityPriceMinor()));
    }
}
