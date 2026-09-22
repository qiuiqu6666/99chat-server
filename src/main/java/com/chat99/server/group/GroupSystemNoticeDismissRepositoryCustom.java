package com.chat99.server.group;

import java.time.Instant;
import java.util.List;

interface GroupSystemNoticeDismissRepositoryCustom {

    /** @return MySQL 实际新插入行数（IGNORE 掉的重复不计） */
    int insertIgnoreBatch(String userId, List<String> noticeIds, Instant dismissedAt);
}
