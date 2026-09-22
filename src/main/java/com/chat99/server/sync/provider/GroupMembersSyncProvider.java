package com.chat99.server.sync.provider;

import com.chat99.server.group.MeGroupMembersChangesService;
import com.chat99.server.sync.SyncDomain;
import com.chat99.server.sync.SyncEnvelope;
import com.chat99.server.sync.SyncProvider;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 群成员域：复用 {@link MeGroupMembersChangesService} 的 v2 协议实现。
 * <p>域特定参数：{@code groupId}（必填）——群成员按 groupId 隔离，各自 revision。
 */
@Component
public class GroupMembersSyncProvider implements SyncProvider {

    /** extraParams key：目标群 ID。 */
    public static final String PARAM_GROUP_ID = "groupId";

    private final MeGroupMembersChangesService delegate;

    public GroupMembersSyncProvider(MeGroupMembersChangesService delegate) {
        this.delegate = delegate;
    }

    @Override
    public SyncDomain domain() {
        return SyncDomain.GROUP_MEMBERS;
    }

    @Override
    public SyncEnvelope snapshot(String userId, String opaqueCursor, int limit, Long snapshotRevision,
                                 Map<String, String> extraParams) {
        String groupId = requireGroupId(extraParams);
        MeGroupMembersChangesService.SnapshotResponse resp =
            delegate.snapshot(groupId, userId, opaqueCursor, limit, snapshotRevision);
        return SyncEnvelope.forSnapshot(
            SyncDomain.GROUP_MEMBERS.path(), resp.snapshotRevision(), resp.opaqueCursor(),
            resp.hasMore(), null, resp.items());
    }

    @Override
    public SyncEnvelope changes(String userId, String opaqueCursor, int limit, Map<String, String> extraParams) {
        String groupId = requireGroupId(extraParams);
        MeGroupMembersChangesService.ChangesResponse resp =
            delegate.changes(groupId, userId, opaqueCursor, limit);
        return SyncEnvelope.forChanges(
            SyncDomain.GROUP_MEMBERS.path(), resp.snapshotRevision(), resp.toRevision(),
            resp.opaqueCursor(), resp.hasMore(), resp.events());
    }

    private static String requireGroupId(Map<String, String> extraParams) {
        String groupId = extraParams == null ? null : extraParams.get(PARAM_GROUP_ID);
        if (groupId == null || groupId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GROUP_ID_REQUIRED");
        }
        return groupId.trim();
    }
}
