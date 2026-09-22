package com.chat99.server.sync.provider;

import com.chat99.server.sync.SyncDomain;
import com.chat99.server.sync.SyncEnvelope;
import com.chat99.server.sync.SyncProvider;
import com.chat99.server.user.MeFriendsChangesService;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 好友通讯录域：复用 {@link MeFriendsChangesService} 的 v2 协议实现。
 */
@Component
public class ContactsSyncProvider implements SyncProvider {

    private final MeFriendsChangesService delegate;

    public ContactsSyncProvider(MeFriendsChangesService delegate) {
        this.delegate = delegate;
    }

    @Override
    public SyncDomain domain() {
        return SyncDomain.CONTACTS;
    }

    @Override
    public SyncEnvelope snapshot(String userId, String opaqueCursor, int limit, Long snapshotRevision,
                                 Map<String, String> extraParams) {
        MeFriendsChangesService.SnapshotResponse resp =
            delegate.snapshot(userId, opaqueCursor, limit, snapshotRevision);
        return SyncEnvelope.forSnapshot(
            SyncDomain.CONTACTS.path(), resp.snapshotRevision(), resp.opaqueCursor(),
            resp.hasMore(), resp.total(), resp.items());
    }

    @Override
    public SyncEnvelope changes(String userId, String opaqueCursor, int limit, Map<String, String> extraParams) {
        MeFriendsChangesService.ChangesResponse resp = delegate.changes(userId, opaqueCursor, limit);
        return SyncEnvelope.forChanges(
            SyncDomain.CONTACTS.path(), resp.snapshotRevision(), resp.toRevision(),
            resp.opaqueCursor(), resp.hasMore(), resp.events());
    }
}
