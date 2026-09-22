package com.chat99.server.group;

import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GroupChangeEventService {

    public record ChangeEventItemView(
        String changeEventId,
        String action,
        String operatorUserId,
        List<String> memberUserIds,
        long occurredAt,
        Integer timelineRank,
        Map<String, Object> detail) {}

    public record UserChangeEventItemView(
        String groupId,
        String changeEventId,
        String action,
        String operatorUserId,
        List<String> memberUserIds,
        long occurredAt,
        Integer timelineRank,
        Map<String, Object> detail) {}

    public record GroupChangeEventsResponse(
        String groupId,
        List<ChangeEventItemView> items,
        long nextSince,
        boolean hasMore) {}

    public record MyGroupChangeEventsResponse(
        List<UserChangeEventItemView> items,
        long nextSince,
        boolean hasMore) {}

    private static final int DEFAULT_GROUP_LIMIT = 50;
    private static final int MAX_GROUP_LIMIT = 200;
    private static final int DEFAULT_MY_LIMIT = 100;
    private static final int MAX_MY_LIMIT = 200;

    private final GroupChangeEventRepository changeEventRepository;
    private final GroupChangeEventMapper mapper;
    private final GroupAccessService access;
    private final GroupProjectionService projection;

    public GroupChangeEventService(GroupChangeEventRepository changeEventRepository,
                                   GroupChangeEventMapper mapper,
                                   GroupAccessService access,
                                   GroupProjectionService projection) {
        this.changeEventRepository = changeEventRepository;
        this.mapper = mapper;
        this.access = access;
        this.projection = projection;
    }

    public GroupChangeEventsResponse listForGroup(String groupId,
                                                  String callerUserId,
                                                  long since,
                                                  int limit,
                                                  String actionsParam) {
        GroupAccessService.validateGroupId(groupId);
        GroupProfile profile = projection.findProfile(groupId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND"));
        if (profile.isDismissed()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
        }
        access.requireMember(groupId, callerUserId);

        int safeLimit = clamp(limit, DEFAULT_GROUP_LIMIT, MAX_GROUP_LIMIT);
        long safeSince = Math.max(since, 0L);
        List<String> actions = GroupChangeEventMapper.parseActions(actionsParam);
        List<GroupChangeEvent> rows = changeEventRepository.findByGroupSinceActions(
            groupId, safeSince, actions, PageRequest.of(0, safeLimit));
        List<ChangeEventItemView> items = rows.stream().map(mapper::toItemView).toList();
        return new GroupChangeEventsResponse(
            groupId,
            items,
            nextSince(items, safeSince),
            items.size() == safeLimit);
    }

    public MyGroupChangeEventsResponse listForUser(String userId,
                                                   long since,
                                                   int limit,
                                                   String actionsParam) {
        int safeLimit = clamp(limit, DEFAULT_MY_LIMIT, MAX_MY_LIMIT);
        long safeSince = Math.max(since, 0L);
        List<String> actions = GroupChangeEventMapper.parseActions(actionsParam);
        List<GroupChangeEvent> rows = changeEventRepository.findForUserSinceActions(
            userId, safeSince, actions, PageRequest.of(0, safeLimit));
        List<UserChangeEventItemView> items = rows.stream().map(mapper::toUserItemView).toList();
        return new MyGroupChangeEventsResponse(
            items,
            nextSinceUser(items, safeSince),
            items.size() == safeLimit);
    }

    private static long nextSince(List<ChangeEventItemView> items, long fallbackSince) {
        if (items.isEmpty()) {
            return fallbackSince;
        }
        return items.get(items.size() - 1).occurredAt();
    }

    private static long nextSinceUser(List<UserChangeEventItemView> items, long fallbackSince) {
        if (items.isEmpty()) {
            return fallbackSince;
        }
        return items.get(items.size() - 1).occurredAt();
    }

    private static int clamp(int value, int defaultValue, int max) {
        if (value <= 0) {
            return defaultValue;
        }
        return Math.min(value, max);
    }
}
