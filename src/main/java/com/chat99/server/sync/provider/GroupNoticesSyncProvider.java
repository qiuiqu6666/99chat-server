package com.chat99.server.sync.provider;

import com.chat99.server.group.MeGroupNoticeChangesService;
import com.chat99.server.sync.SyncDomain;
import com.chat99.server.sync.SyncEnvelope;
import com.chat99.server.sync.SyncProvider;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 群通知收件箱域：复用 {@link MeGroupNoticeChangesService} 的 v2 协议实现。
 */
@Component
public class GroupNoticesSyncProvider implements SyncProvider {

    private final MeGroupNoticeChangesService delegate;

    public GroupNoticesSyncProvider(MeGroupNoticeChangesService delegate) {
        this.delegate = delegate;
    }

    @Override
    public SyncDomain domain() {
        return SyncDomain.GROUP_NOTICES;
    }

    @Override
    public SyncEnvelope snapshot(String userId, String opaqueCursor, int limit, Long snapshotRevision,
                                 Map<String, String> extraParams) {
        MeGroupNoticeChangesService.SnapshotResponse resp =
            delegate.snapshot(userId, opaqueCursor, limit, snapshotRevision);
        return SyncEnvelope.forSnapshot(
            SyncDomain.GROUP_NOTICES.path(), resp.snapshotRevision(), resp.opaqueCursor(),
            resp.hasMore(), null, resp.items());
    }

    @Override
    public SyncEnvelope changes(String userId, String opaqueCursor, int limit, Map<String, String> extraParams) {
        MeGroupNoticeChangesService.ChangesResponse resp = delegate.changes(userId, opaqueCursor, limit);
        return SyncEnvelope.forChanges(
            SyncDomain.GROUP_NOTICES.path(), resp.snapshotRevision(), resp.toRevision(),
            resp.opaqueCursor(), resp.hasMore(), resp.events());
    }
}
