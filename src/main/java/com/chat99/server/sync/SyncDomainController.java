package com.chat99.server.sync;

import com.chat99.server.sync.provider.GroupMembersSyncProvider;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 业务数据双轨同步统一入口（Phase 4）：
 * <ul>
 *   <li>{@code GET /sync/{domain}/snapshot?limit=&cursor=&snapshotRevision=&groupId=}</li>
 *   <li>{@code GET /sync/{domain}/changes?cursor=&limit=&groupId=}</li>
 * </ul>
 * domain ∈ contacts | groups | groupMembers | groupNotices（{@link SyncDomain}）。
 *
 * <p>注意与旧 {@code /me/sync/*}（通讯录/照片/视频上传同步，{@link SyncController}）无关；
 * 本路由只服务业务权威数据（好友 / 群展示 / 群成员 / 群通知）。
 * 各域旧接口（{@code /me/friends/changes/v2} 等）保留 6 个月 deprecated。
 */
@RestController
public class SyncDomainController {

    private final Map<SyncDomain, SyncProvider> providersByDomain = new EnumMap<>(SyncDomain.class);

    public SyncDomainController(@Lazy List<SyncProvider> providers) {
        for (SyncProvider provider : providers) {
            providersByDomain.put(provider.domain(), provider);
        }
    }

    @GetMapping("/sync/{domain}/snapshot")
    public SyncEnvelope snapshot(Authentication auth,
                                 @PathVariable String domain,
                                 @RequestParam(required = false) String cursor,
                                 @RequestParam(required = false) Integer limit,
                                 @RequestParam(required = false) Long snapshotRevision,
                                 @RequestParam(required = false) String groupId) {
        SyncProvider provider = resolve(domain);
        Map<String, String> extra = extraParams(groupId);
        return provider.snapshot(
            (String) auth.getPrincipal(), cursor, limit == null ? 0 : limit, snapshotRevision, extra);
    }

    @GetMapping("/sync/{domain}/changes")
    public SyncEnvelope changes(Authentication auth,
                                @PathVariable String domain,
                                @RequestParam(required = false) String cursor,
                                @RequestParam(required = false) Integer limit,
                                @RequestParam(required = false) String groupId) {
        SyncProvider provider = resolve(domain);
        Map<String, String> extra = extraParams(groupId);
        return provider.changes(
            (String) auth.getPrincipal(), cursor, limit == null ? 0 : limit, extra);
    }

    private SyncProvider resolve(String domain) {
        SyncDomain parsed = SyncDomain.fromPath(domain);
        if (parsed == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SYNC_DOMAIN_NOT_FOUND");
        }
        SyncProvider provider = providersByDomain.get(parsed);
        if (provider == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SYNC_DOMAIN_NOT_FOUND");
        }
        return provider;
    }

    private static Map<String, String> extraParams(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return Map.of();
        }
        return Map.of(GroupMembersSyncProvider.PARAM_GROUP_ID, groupId.trim());
    }
}
