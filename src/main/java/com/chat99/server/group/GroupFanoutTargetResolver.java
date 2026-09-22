package com.chat99.server.group;

import com.chat99.server.im.ImAdminClient;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 群事件 fanout targets：优先本地投影成员，空再 fallback IM 分页。
 * 万人群热路径禁止默认同步扫 IM 全员。
 */
@Component
public class GroupFanoutTargetResolver {

    private static final Logger log = LoggerFactory.getLogger(GroupFanoutTargetResolver.class);

    private final GroupProjectionService projection;
    private final ImAdminClient im;
    private final GroupFanoutProperties props;

    public GroupFanoutTargetResolver(GroupProjectionService projection,
                                     ImAdminClient im,
                                     GroupFanoutProperties props) {
        this.projection = projection;
        this.im = im;
        this.props = props == null ? GroupFanoutProperties.defaults() : props;
    }

    public List<String> resolveMemberTargets(String groupId) {
        return resolveMemberTargetsUnion(groupId, List.of());
    }

    public List<String> resolveMemberTargetsUnion(String groupId, List<String> extraUserIds) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (groupId == null || groupId.isBlank()) {
            addExtras(out, extraUserIds);
            return List.copyOf(out);
        }
        String gid = groupId.trim();
        boolean usedImFallback = false;
        if (props.localTargetsEnabled()) {
            for (String id : projection.listLocalMemberUserIds(gid)) {
                addId(out, id);
            }
        }
        if (out.isEmpty()) {
            usedImFallback = true;
            for (String id : im.listGroupMemberUserIds(gid, props.maxMembers())) {
                addId(out, id);
                if (out.size() >= props.maxMembers()) {
                    break;
                }
            }
        }
        addExtras(out, extraUserIds);
        if (out.size() > props.maxMembers()) {
            List<String> trimmed = new ArrayList<>(props.maxMembers());
            for (String id : out) {
                trimmed.add(id);
                if (trimmed.size() >= props.maxMembers()) {
                    break;
                }
            }
            log.warn("group fanout targets truncated groupId={} targetCount={} maxMembers={} imFallback={}",
                gid, out.size(), props.maxMembers(), usedImFallback);
            return trimmed;
        }
        log.debug("group fanout targets resolved groupId={} targetCount={} imFallback={}",
            gid, out.size(), usedImFallback);
        return List.copyOf(out);
    }

    private static void addExtras(Set<String> out, List<String> extraUserIds) {
        if (extraUserIds == null || extraUserIds.isEmpty()) {
            return;
        }
        for (String id : extraUserIds) {
            addId(out, id);
        }
    }

    private static void addId(Set<String> out, String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        out.add(id.trim());
    }
}
