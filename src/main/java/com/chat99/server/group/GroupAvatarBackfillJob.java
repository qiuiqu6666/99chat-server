package com.chat99.server.group;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 群头像兜底：定时把 avatar_url 为 NULL/空 的群补成默认头像（来自 GroupAvatarDefaults）。
 * <p>
 * 适用场景：
 * <ul>
 *   <li>历史数据迁移遗漏</li>
 *   <li>从 IM 投影时未取到头像 URL</li>
 *   <li>任何绕过 GroupCreateService 的写入路径</li>
 * </ul>
 * 频次低：默认每天凌晨 03:15，单批 200 条，足够把极端情况下漏的数据补齐。
 */
@Component
@ConditionalOnProperty(name = "chat99.group.avatar-backfill-enabled", havingValue = "true", matchIfMissing = true)
public class GroupAvatarBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(GroupAvatarBackfillJob.class);
    private static final int PAGE_SIZE = 200;

    private final GroupProfileRepository profileRepository;
    private final GroupAvatarDefaults avatarDefaults;

    public GroupAvatarBackfillJob(GroupProfileRepository profileRepository,
                                  GroupAvatarDefaults avatarDefaults) {
        this.profileRepository = profileRepository;
        this.avatarDefaults = avatarDefaults;
    }

    /** 默认每天凌晨 03:15 跑一次；可由 GROUP_AVATAR_BACKFILL_CRON 覆盖。 */
    @Scheduled(cron = "${chat99.group.avatar-backfill-cron:0 15 3 * * ?}")
    public void backfillMissingAvatars() {
        String defaultUrl = avatarDefaults.defaultAvatarUrl();
        if (defaultUrl == null || defaultUrl.isBlank()) {
            log.debug("group avatar backfill skipped: no default url configured");
            return;
        }
        int batchSize = 0;
        while (true) {
            List<GroupProfile> page = profileRepository.findMissingAvatar(PageRequest.of(0, PAGE_SIZE));
            if (page.isEmpty()) {
                break;
            }
            int updated = 0;
            for (GroupProfile p : page) {
                String current = p.getAvatarUrl();
                if (current == null || current.isBlank()) {
                    p.setAvatarUrl(defaultUrl);
                    profileRepository.save(p);
                    updated++;
                }
            }
            batchSize += updated;
            if (updated < PAGE_SIZE) {
                break;
            }
        }
        if (batchSize > 0) {
            log.info("group avatar backfill updated {} group(s) with default url", batchSize);
        }
    }
}
