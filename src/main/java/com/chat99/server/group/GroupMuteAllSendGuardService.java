package com.chat99.server.group;

import com.chat99.server.im.GroupTipImSupport;
import com.chat99.server.im.ImUserIdService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 软全员禁言：投影 {@code shut_up_all=true} 时拦截普通成员非 tip 发言；
 * 纯 {@code group_tip} 放行。IM 原生 ShutUpAllMember 应保持 Off。
 */
@Service
public class GroupMuteAllSendGuardService {

    public static final String REJECT_CODE = "GROUP_ALL_MUTED";

    private static final Logger log = LoggerFactory.getLogger(GroupMuteAllSendGuardService.class);

    private final GroupProjectionService projection;
    private final GroupMemberRepository memberRepository;
    private final ImUserIdService imUserIdService;
    private final ObjectMapper json;

    public GroupMuteAllSendGuardService(GroupProjectionService projection,
                                        GroupMemberRepository memberRepository,
                                        ImUserIdService imUserIdService,
                                        ObjectMapper json) {
        this.projection = projection;
        this.memberRepository = memberRepository;
        this.imUserIdService = imUserIdService;
        this.json = json;
    }

    /**
     * @return 拒绝码，或 empty 表示放行
     */
    public Optional<String> evaluate(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return Optional.empty();
        }
        String groupId = str(body.get("GroupId"));
        if (groupId == null || !projection.isShutUpAll(groupId)) {
            return Optional.empty();
        }
        String from = str(body.get("From_Account"));
        if (from == null) {
            return Optional.of(REJECT_CODE);
        }
        if (imUserIdService.isSpecialImAccount(from)) {
            return Optional.empty();
        }
        if (isAdminOrOwner(groupId, from)) {
            return Optional.empty();
        }
        if (GroupTipImSupport.isPureGroupTipMessage(body, json)) {
            return Optional.empty();
        }
        log.debug("group mute-all reject groupId={} from={}", groupId, from);
        return Optional.of(REJECT_CODE);
    }

    private boolean isAdminOrOwner(String groupId, String fromAccount) {
        Integer role = findRole(groupId, fromAccount);
        if (role == null) {
            String businessId = imUserIdService.findBusinessUserId(fromAccount).orElse(null);
            if (businessId != null && !businessId.equals(fromAccount)) {
                role = findRole(groupId, businessId);
            }
        }
        if (role == null) {
            // 缺投影：按普通成员拦截，避免误放行闲聊
            log.debug("group mute-all missing role groupId={} from={}", groupId, fromAccount);
            return false;
        }
        return role >= GroupRoleCodec.ADMIN;
    }

    private Integer findRole(String groupId, String userId) {
        return memberRepository.findById(new GroupMemberId(groupId, userId))
            .map(GroupMember::getRole)
            .orElse(null);
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
