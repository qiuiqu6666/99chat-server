package com.chat99.server.group;

import com.chat99.server.group.GroupPrivacyProperties;
import com.chat99.server.realtime.GroupRealtimePublisher;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GroupPrivacyService {

    private static final Logger log = LoggerFactory.getLogger(GroupPrivacyService.class);

    public record GroupPrivacyView(boolean privacyProtectionEnabled) {}

    private final GroupSettingsRepository settingsRepository;
    private final GroupPrivacyProperties props;
    private final GroupRealtimePublisher groupRealtime;
    private final GroupAccessService access;
    private final GroupFanoutTargetResolver fanoutTargets;

    public GroupPrivacyService(GroupSettingsRepository settingsRepository,
                               GroupPrivacyProperties props,
                               GroupRealtimePublisher groupRealtime,
                               GroupAccessService access,
                               GroupFanoutTargetResolver fanoutTargets) {
        this.settingsRepository = settingsRepository;
        this.props = props;
        this.groupRealtime = groupRealtime;
        this.access = access;
        this.fanoutTargets = fanoutTargets;
    }

    public GroupPrivacyView get(String groupId, String callerUserId) {
        validateGroupId(groupId);
        requireGroupMember(groupId, callerUserId);
        return toView(resolveSettings(groupId));
    }

    @Transactional
    public GroupPrivacyView update(String groupId, String callerUserId, boolean privacyProtectionEnabled) {
        validateGroupId(groupId);
        requireGroupAdmin(groupId, callerUserId);

        GroupSettings row = settingsRepository.findById(groupId).orElseGet(() -> {
            GroupSettings created = new GroupSettings();
            created.setGroupId(groupId);
            return created;
        });
        boolean oldValue = row.isPrivacyProtectionEnabled();
        row.setPrivacyProtectionEnabled(privacyProtectionEnabled);
        settingsRepository.save(row);
        notifyPrivacyChanged(groupId, callerUserId, privacyProtectionEnabled);

        log.info("[群隐私设置变更] groupId={}, operator={}, enabled={} -> {}",
            groupId, callerUserId, oldValue, privacyProtectionEnabled);

        return toView(row);
    }

    private void notifyPrivacyChanged(String groupId, String operatorUserId, boolean enabled) {
        List<String> targets = fanoutTargets.resolveMemberTargets(groupId);
        if (targets.isEmpty()) {
            return;
        }
        long occurredAtMs = System.currentTimeMillis();
        Map<String, Object> enriched = GroupRealtimeDetailFactory.enrichWithOccurredAt(
            Map.of("privacyProtectionEnabled", enabled), occurredAtMs);
        groupRealtime.publish(
            groupId,
            GroupRealtimePublisher.ACTION_GROUP_PRIVACY_CHANGED,
            operatorUserId,
            List.of(),
            targets,
            enriched,
            GroupChangeIdGenerator.newChangeEventId(),
            occurredAtMs,
            GroupTimelineRank.forAction(GroupRealtimePublisher.ACTION_GROUP_PRIVACY_CHANGED));
    }

    private GroupSettings resolveSettings(String groupId) {
        return settingsRepository.findById(groupId).orElseGet(() -> {
            GroupSettings defaults = new GroupSettings();
            defaults.setGroupId(groupId);
            defaults.setPrivacyProtectionEnabled(props.defaultPrivacyProtectionEnabled());
            return defaults;
        });
    }

    private GroupPrivacyView toView(GroupSettings row) {
        return new GroupPrivacyView(row.isPrivacyProtectionEnabled());
    }

    private void validateGroupId(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
    }

    private void requireGroupMember(String groupId, String userId) {
        access.requireMember(groupId, userId);
    }

    private void requireGroupAdmin(String groupId, String userId) {
        access.requireAdminRole(groupId, userId);
    }
}
