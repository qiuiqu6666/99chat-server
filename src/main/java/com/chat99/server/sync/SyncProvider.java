package com.chat99.server.sync;

import java.util.List;
import java.util.Map;

/**
 * 业务数据双轨同步 Provider：每个域（contacts / groups / groupMembers / groupNotices）
 * 实现本接口，统一挂到 {@code GET /sync/{domain}/snapshot} 与 {@code GET /sync/{domain}/changes}。
 *
 * <p>协议约定（详见 docs/sync-protocol.md）：
 * <ul>
 *   <li>{@code snapshot} — 全量分页：{@code (snapshotRevision, opaqueCursor, hasMore, items[], total?, serverTime)}</li>
 *   <li>{@code changes} — 增量追赶：{@code (snapshotRevision, toRevision, opaqueCursor, hasMore, events[], serverTime)}</li>
 *   <li>{@code opaqueCursor} — 服务端生成 base64url(json)，客户端原样回传，不可解读/拼装</li>
 *   <li>{@code itemVersion} — 单条实体单调递增（snapshot 去重）</li>
 *   <li>{@code deleted=true} — tombstone，客户端删除本地缓存</li>
 *   <li>{@code eventId} — 增量事件幂等去重</li>
 * </ul>
 *
 * <p>实现类直接复用各域已有的 v2 Service（{@code snapshot/changes} 方法），
 * 将响应重新包装为统一 envelope，避免重复实现一致性逻辑。
 */
public interface SyncProvider {

    /** 本 Provider 负责的域。 */
    SyncDomain domain();

    /**
     * 全量快照分页。
     *
     * @param userId           当前用户（业务 user_id）
     * @param opaqueCursor     上一页返回的 cursor；空表示第一页
     * @param limit            页大小；&lt;=0 用默认
     * @param snapshotRevision 可选：指定一致性快照版本；null 用当前最新
     * @param extraParams      域特定参数（如 groupMembers 的 groupId）
     * @return 统一 envelope
     */
    SyncEnvelope snapshot(String userId, String opaqueCursor, int limit, Long snapshotRevision,
                          Map<String, String> extraParams);

    /**
     * 增量追赶。
     *
     * @param userId       当前用户
     * @param opaqueCursor 上次 snapshot/changes 返回的 cursor；空表示从头
     * @param limit        页大小；&lt;=0 用默认
     * @param extraParams  域特定参数（如 groupMembers 的 groupId）
     * @return 统一 envelope
     */
    SyncEnvelope changes(String userId, String opaqueCursor, int limit, Map<String, String> extraParams);
}
