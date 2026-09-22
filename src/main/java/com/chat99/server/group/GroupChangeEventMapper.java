package com.chat99.server.group;

import com.chat99.server.realtime.GroupRealtimePublisher;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GroupChangeEventMapper {

    private static final Logger log = LoggerFactory.getLogger(GroupChangeEventMapper.class);
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> STRING_OBJECT_MAP = new TypeReference<>() {};

    private final ObjectMapper json;

    public GroupChangeEventMapper(ObjectMapper json) {
        this.json = json;
    }

    public GroupChangeEventService.ChangeEventItemView toItemView(GroupChangeEvent row) {
        return new GroupChangeEventService.ChangeEventItemView(
            row.getChangeEventId(),
            row.getAction(),
            row.getOperatorUserId(),
            readMemberUserIds(row.getMemberUserIdsJson()),
            row.getOccurredAt(),
            row.getTimelineRank(),
            readDetail(row.getDetailJson()));
    }

    public GroupChangeEventService.UserChangeEventItemView toUserItemView(GroupChangeEvent row) {
        return new GroupChangeEventService.UserChangeEventItemView(
            row.getGroupId(),
            row.getChangeEventId(),
            row.getAction(),
            row.getOperatorUserId(),
            readMemberUserIds(row.getMemberUserIdsJson()),
            row.getOccurredAt(),
            row.getTimelineRank(),
            readDetail(row.getDetailJson()));
    }

    Set<String> readMemberUserIdSet(String memberUserIdsJson) {
        return new LinkedHashSet<>(readMemberUserIds(memberUserIdsJson));
    }

    private List<String> readMemberUserIds(String memberUserIdsJson) {
        if (memberUserIdsJson == null || memberUserIdsJson.isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = json.readValue(memberUserIdsJson, STRING_LIST);
            return parsed == null ? List.of() : parsed;
        } catch (Exception e) {
            log.debug("group change event member ids parse failed: {}", e.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> readDetail(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = json.readValue(detailJson, STRING_OBJECT_MAP);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            log.debug("group change event detail parse failed: {}", e.getMessage());
            return Map.of();
        }
    }

    static List<String> defaultMemberChangeActions() {
        return List.of(
            GroupRealtimePublisher.ACTION_MEMBER_ADDED,
            GroupRealtimePublisher.ACTION_MEMBER_REMOVED,
            GroupRealtimePublisher.ACTION_MEMBER_LEFT);
    }

    static List<String> parseActions(String actionsParam) {
        if (actionsParam == null || actionsParam.isBlank()) {
            return defaultMemberChangeActions();
        }
        List<String> parsed = java.util.Arrays.stream(actionsParam.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .distinct()
            .toList();
        return parsed.isEmpty() ? defaultMemberChangeActions() : parsed;
    }
}
