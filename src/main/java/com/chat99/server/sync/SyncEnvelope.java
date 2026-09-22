package com.chat99.server.sync;

import java.util.List;

/**
 * /sync/{domain}/{snapshot|changes} 统一响应 envelope。
 *
 * <pre>{@code
 * {
 *   "domain": "contacts",
 *   "snapshotRevision": 42,     // 当前快照一致版本
 *   "toRevision": 45,           // 仅 changes：本次拉取推进到的 revision
 *   "opaqueCursor": "eyJkIjoi...", // 下一页 / 下次增量游标（base64url）
 *   "hasMore": false,
 *   "total": 128,               // 仅 snapshot（可选）：活跃实体总数
 *   "serverTime": 1730000000000,
 *   "items": [ ... ],           // snapshot：实体快照项
 *   "events": [ ... ]           // changes：变更事件
 * }
 * }</pre>
 *
 * <p>items / events 元素结构由各域定义，但每个元素必含
 * {@code id} / {@code itemVersion} / {@code deleted}（tombstone），
 * events 元素额外含 {@code eventId} / {@code operation}（upsert|delete）。
 */
public record SyncEnvelope(
    String domain,
    long snapshotRevision,
    Long toRevision,
    String opaqueCursor,
    boolean hasMore,
    Long total,
    long serverTime,
    List<?> items,
    List<?> events) {

    public static SyncEnvelope forSnapshot(String domain, long snapshotRevision, String opaqueCursor,
                                           boolean hasMore, Long total, List<?> items) {
        return new SyncEnvelope(domain, snapshotRevision, null, opaqueCursor, hasMore, total,
            System.currentTimeMillis(), items, null);
    }

    public static SyncEnvelope forChanges(String domain, long snapshotRevision, long toRevision,
                                          String opaqueCursor, boolean hasMore, List<?> events) {
        return new SyncEnvelope(domain, snapshotRevision, toRevision, opaqueCursor, hasMore, null,
            System.currentTimeMillis(), null, events);
    }
}
