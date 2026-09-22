package com.chat99.server.group;

import com.chat99.server.im.ImCallbackVerifier;
import com.chat99.server.im.ImUserIdService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ImGroupBeforeCreateCallbackService {

    public static final String CMD_GROUP_BEFORE_CREATE = "Group.CallbackBeforeCreateGroup";
    private static final Logger log = LoggerFactory.getLogger(ImGroupBeforeCreateCallbackService.class);

    private final GroupCreateLimitConfigService config;
    private final UserOwnedGroupService ownedGroupService;
    private final GroupJoinLimitService joinLimitService;
    private final ImUserIdService imUserIdService;
    private final ImCallbackVerifier callbackVerifier;
    private final ObjectMapper json;

    public ImGroupBeforeCreateCallbackService(GroupCreateLimitConfigService config,
                                              UserOwnedGroupService ownedGroupService,
                                              GroupJoinLimitService joinLimitService,
                                              ImUserIdService imUserIdService,
                                              ImCallbackVerifier callbackVerifier,
                                              ObjectMapper json) {
        this.config = config;
        this.ownedGroupService = ownedGroupService;
        this.joinLimitService = joinLimitService;
        this.imUserIdService = imUserIdService;
        this.callbackVerifier = callbackVerifier;
        this.json = json;
    }

    public ImCallbackVerifier.ImCallbackResponse handle(String sdkAppId,
                                                        String command,
                                                        String callbackToken,
                                                        String sign,
                                                        String requestTime,
                                                        String rawBody) {
        if (!CMD_GROUP_BEFORE_CREATE.equals(command) || !config.isEnabled()) {
            return null;
        }

        Map<String, Object> body = parseBody(rawBody);
        verifyAuth(callbackToken, sign, requestTime);

        String ownerIm = str(body.get("Owner_Account"));
        String groupType = str(body.get("Type"));
        if (ownerIm == null || ownerIm.isBlank() || groupType == null || groupType.isBlank()) {
            return ImCallbackVerifier.ImCallbackResponse.ok();
        }

        Set<String> candidatesIm = new LinkedHashSet<>();
        candidatesIm.add(ownerIm.trim());
        candidatesIm.addAll(extractMemberAccounts(body.get("MemberList")));
        // 回调账号为 IM 号；限额按业务号
        Set<String> candidates = toBusinessCandidates(candidatesIm);
        String owner = imUserIdService.toBusinessForDisplay(ownerIm.trim());

        if (GroupCreateLimitConfigService.isCommunity(groupType)) {
            boolean createAllowed = ownedGroupService.canCreate(owner, "Community");
            if (config.isLogOnly()) {
                log.info("group beforeCreate communityCreate owner={} allowed={} enforce={}",
                    owner, createAllowed, config.isEnforce());
            }
            if (!createAllowed && config.isEnforce()) {
                log.info("group beforeCreate rejected owner={} type=Community code={}",
                    owner, GroupJoinLimitService.CODE_COMMUNITY_CREATE);
                return ImCallbackVerifier.ImCallbackResponse.reject(
                    GroupJoinLimitService.CODE_COMMUNITY_CREATE);
            }
        }

        List<GroupJoinLimitExceededException.OverLimitUser> over =
            joinLimitService.findOverLimitUsers(candidates, groupType);
        boolean joinAllowed = over.isEmpty() || !config.isEnforce();
        if (config.isLogOnly()) {
            log.info("group beforeCreate joinCheck owner={} type={} overCount={} enforce={}",
                owner, groupType, over.size(), config.isEnforce());
        }
        if (!joinAllowed) {
            String code = joinLimitService.joinRejectCode(groupType);
            log.info("group beforeCreate rejected owner={} type={} code={} over={}",
                owner, groupType, code, over.size());
            return ImCallbackVerifier.ImCallbackResponse.reject(code);
        }
        return ImCallbackVerifier.ImCallbackResponse.ok();
    }

    private Set<String> toBusinessCandidates(Set<String> imAccounts) {
        Set<String> out = new LinkedHashSet<>();
        Map<String, String> mapped = imUserIdService.toBusinessForDisplayBatch(imAccounts);
        for (String raw : imAccounts) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String id = raw.trim();
            if (imUserIdService.isSpecialImAccount(id)) {
                continue;
            }
            out.add(mapped.getOrDefault(id, id));
        }
        return out;
    }

    private void verifyAuth(String callbackToken, String sign, String requestTime) {
        if (sign != null && !sign.isBlank()) {
            callbackVerifier.verifySignature(sign, requestTime);
        } else {
            callbackVerifier.verifyQueryToken(callbackToken);
        }
    }

    private Map<String, Object> parseBody(String rawBody) {
        try {
            return json.readValue(rawBody, new TypeReference<>() {});
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_CALLBACK_BODY");
        }
    }

    @SuppressWarnings("unchecked")
    static List<String> extractMemberAccounts(Object rawList) {
        List<String> out = new ArrayList<>();
        if (!(rawList instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Object acc = map.get("Member_Account");
                if (acc != null && !acc.toString().isBlank()) {
                    out.add(acc.toString().trim());
                }
            } else if (item != null && !item.toString().isBlank()) {
                out.add(item.toString().trim());
            }
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
