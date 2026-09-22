package com.chat99.server.group;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserOwnedGroupRepository extends JpaRepository<UserOwnedGroup, String> {

    long countByOwnerUserIdAndGroupType(String ownerUserId, String groupType);
}
