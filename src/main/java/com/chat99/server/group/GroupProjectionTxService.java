package com.chat99.server.group;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 独立事务执行群投影写入，避免与 {@link GroupCreateService} 等同一大事务，
 * 以及 IM 建群回调并发写库时 Hibernate 将外层事务标记为 rollback-only。
 */
@Service
public class GroupProjectionTxService {

    private final GroupProjectionService projection;

    public GroupProjectionTxService(GroupProjectionService projection) {
        this.projection = projection;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW,
        noRollbackFor = DataIntegrityViolationException.class)
    public GroupProjectionService.GroupFullSyncResult syncFullGroupFromIm(String groupId) {
        return projection.syncFullGroupFromIm(groupId);
    }

    /** 建群后本地 seed，不回源 IM 成员列表。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW,
        noRollbackFor = DataIntegrityViolationException.class)
    public void seedGroupAfterCreate(String groupId,
                                     String creatorUserId,
                                     String groupType,
                                     String groupName,
                                     String avatarUrl,
                                     String introduction,
                                     java.util.List<String> memberUserIds) {
        projection.seedGroupAfterCreate(
            groupId, creatorUserId, groupType, groupName, avatarUrl, introduction, memberUserIds);
    }
}
