package com.chat99.server.integration;

import com.chat99.server.group.GroupFanoutTargetResolver;
import com.chat99.server.realtime.GroupRealtimePublisher;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IntegrationGroupLiveNotifyService {

    public static final String ACTION_GROUP_LIVE_CHANGED = GroupRealtimePublisher.ACTION_GROUP_LIVE_CHANGED;

    private final GroupFanoutTargetResolver fanout;
    private final GroupRealtimePublisher realtime;

    public IntegrationGroupLiveNotifyService(GroupFanoutTargetResolver fanout,
                                               GroupRealtimePublisher realtime) {
        this.fanout = fanout;
        this.realtime = realtime;
    }

    public Map<String, Object> notify(
        String groupId,
        String operatorUserId,
        String changeEventId,
        Map<String, Object> detail
    ) {
        if (groupId == null || groupId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (changeEventId == null || changeEventId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        String gid = groupId.trim();
        List<String> targets = fanout.resolveMemberTargets(gid);
        Map<String, Object> safeDetail = detail == null ? Map.of() : new LinkedHashMap<>(detail);
        long now = Instant.now().toEpochMilli();
        realtime.publish(
            gid,
            ACTION_GROUP_LIVE_CHANGED,
            operatorUserId,
            targets,
            targets,
            safeDetail,
            changeEventId.trim(),
            now,
            null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("targetCount", targets.size());
        return out;
    }
}
