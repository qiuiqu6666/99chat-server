package com.chat99.server.integration;

import com.chat99.server.group.GroupMemberRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IntegrationGroupLiveMemberFilterService {

    private final GroupMemberRepository members;

    public IntegrationGroupLiveMemberFilterService(GroupMemberRepository members) {
        this.members = members;
    }

    public Map<String, Object> filter(String userId, List<String> groupIds) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        String uid = userId.trim();
        List<String> requested = normalizeGroupIds(groupIds);
        if (requested.isEmpty()) {
            return Map.of("groupIds", List.of());
        }
        Set<String> memberOf = new LinkedHashSet<>();
        Set<String> requestedSet = new LinkedHashSet<>(requested);
        for (var member : members.findByUserIdAndGroupIdIn(uid, requestedSet)) {
            if (member.getGroupId() != null && !member.getGroupId().isBlank()) {
                memberOf.add(member.getGroupId().trim());
            }
        }
        List<String> out = requested.stream().filter(memberOf::contains).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groupIds", out);
        return body;
    }

    private static List<String> normalizeGroupIds(List<String> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String gid : groupIds) {
            if (gid != null && !gid.isBlank()) {
                out.add(gid.trim());
            }
        }
        return new ArrayList<>(out);
    }
}
