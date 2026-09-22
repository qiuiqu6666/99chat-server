package com.chat99.server.sync.provider;

import com.chat99.server.sync.SyncDomain;
import com.chat99.server.sync.SyncEnvelope;
import com.chat99.server.sync.SyncProvider;
import com.chat99.server.group.MeGroupsChangesService;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 群展示域：复用 {@link MeGroupsChangesService} 的 v2 协议实现。
 */
@Component
public class GroupsSyncProvider implements SyncProvider {

    private final MeGroupsChangesService delegate;

    public GroupsSyncProvider(MeGroupsChangesService delegate) {
        this.delegate = delegate;
    }

    @Override
    public SyncDomain domain() {
        return SyncDomain.GROUPS;
    }

    @Override
    public SyncEnvelope snapshot(String userId, String opaqueCursor, int limit, Long snapshotRevision,
                                 Map<String, String> extraParams) {
        MeGroupsChangesService.SnapshotResponse resp =
            delegate.snapshot(userId, opaqueCursor, limit, snapshotRevision);
        return SyncEnvelope.forSnapshot(
            SyncDomain.GROUPS.path(), resp.snapshotRevision(), resp.opaqueCursor(),
            resp.hasMore(), null, resp.items());
    }

    @Override
    public SyncEnvelope changes(String userId, String opaqueCursor, int limit, Map<String, String> extraParams) {
        MeGroupsChangesService.ChangesResponse resp = delegate.changes(userId, opaqueCursor, limit);
        return SyncEnvelope.forChanges(
            SyncDomain.GROUPS.path(), resp.snapshotRevision(), resp.toRevision(),
            resp.opaqueCursor(), resp.hasMore(), resp.events());
    }
}
