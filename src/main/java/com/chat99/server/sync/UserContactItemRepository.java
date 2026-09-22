package com.chat99.server.sync;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface UserContactItemRepository extends JpaRepository<UserContactItem, Long>,
    JpaSpecificationExecutor<UserContactItem> {

    Optional<UserContactItem> findByUserIdAndLocalContactId(String userId, String localContactId);

    List<UserContactItem> findByUserIdAndStatusOrderByDisplayNameAsc(String userId, int status);

    Page<UserContactItem> findByStatusAndPlatformUserFalseAndIdGreaterThanOrderByIdAsc(
        int status, long id, Pageable pageable);
}
