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

/**
 * IM 侧拉人/申请入群前拦截：{@link #CMD_BEFORE_INVITE} / {@link #CMD_BEFORE_APPLY}。
 */
@Service
public class ImGroupBeforeJoinCallbackService {

    public static final String CMD_BEFORE_INVITE = "Group.CallbackBeforeInviteJoinGroup";
    public static final String CMD_BEFORE_APPLY = "Group.CallbackBeforeApplyJoinGroup";
    private static final Logger log = LoggerFactory.getLogger(ImGroupBeforeJoinCallbackService.class);

    private final GroupCreateLimitConfigService config;
    private final GroupJoinLimitService joinLimitService;
    private final ImUserIdService imUserIdService;
    private final GroupProfileRepository profileRepository;
    private final ImCallbackVerifier callbackVerifier;
    private final ObjectMapper json;

    public ImGroupBeforeJoinCallbackService(GroupCreateLimitConfigService config,
                                            GroupJoinLimitService joinLimitService,
                                            ImUserIdService imUserIdService,
                                            GroupProfileRepository profileRepository,
                                            ImCallbackVerifier callbackVerifier,
                                            ObjectMapper json) {
        this.config = config;
        this.joinLimitService = joinLimitService;
        this.imUserIdService = imUserIdService;
        this.profileRepository = profileRepository;
        this.callbackVerifier = callbackVerifier;
        this.json = json;
    }

    public ImCallbackVerifier.ImCallbackResponse handle(String sdkAppId,
                                                        String command,
                                                        String callbackToken,
                                                        String sign,
                                                        String requestTime,
                                                        String rawBody) {
        if (!CMD_BEFORE_INVITE.equals(command) && !CMD_BEFORE_APPLY.equals(command)) {
            return null;
        }
        if (!config.isEnabled()) {
            return null;
        }

        Map<String, Object> body = parseBody(rawBody);
        verifyAuth(callbackToken, sign, requestTime);

        String groupId = str(body.get("GroupId"));
        String groupType = resolveGroupType(groupId, str(body.get("Type")));
        Set<String> candidatesIm = new LinkedHashSet<>();
        if (CMD_BEFORE_APPLY.equals(command)) {
            String requestor = str(body.get("Requestor_Account"));
            if (requestor != null && !requestor.isBlank()) {
                candidatesIm.add(requestor.trim());
            }
        } else {
            candidatesIm.addAll(extractDestinationMembers(body.get("DestinationMembers")));
        }
        if (candidatesIm.isEmpty()) {
            return ImCallbackVerifier.ImCallbackResponse.ok();
        }

        Set<String> candidates = toBusinessCandidates(candidatesIm);
        List<GroupJoinLimitExceededException.OverLimitUser> over =
            joinLimitService.findOverLimitUsers(candidates, groupType);
        if (config.isLogOnly()) {
            log.info("group beforeJoin cmd={} groupId={} type={} candidates={} over={} enforce={}",
                command, groupId, groupType, candidates.size(), over.size(), config.isEnforce());
        }
        if (!over.isEmpty() && config.isEnforce()) {
            String code = joinLimitService.joinRejectCode(groupType);
            log.info("group beforeJoin rejected cmd={} groupId={} code={} over={}",
                command, groupId, code, over.size());
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

    private String resolveGroupType(String groupId, String typeFromCallback) {
        if (typeFromCallback != null && !typeFromCallback.isBlank()) {
            return typeFromCallback.trim();
        }
        if (groupId == null || groupId.isBlank()) {
            return null;
        }
        return profileRepository.findById(groupId)
            .map(GroupProfile::getGroupType)
            .orElse(null);
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
    private static List<String> extractDestinationMembers(Object rawList) {
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
            }
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
