package com.chat99.server.user;

import com.chat99.server.user.FriendRequest.Status;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FriendRequestRepository extends JpaRepository<FriendRequest, Long> {

    Optional<FriendRequest> findByFromUserIdAndToUserIdAndStatus(
        String fromUserId, String toUserId, Status status);

    Optional<FriendRequest> findByIdAndToUserId(Long id, String toUserId);

    Optional<FriendRequest> findByIdAndFromUserId(Long id, String fromUserId);

    List<FriendRequest> findByToUserIdAndStatusOrderByCreatedAtDesc(
        String toUserId, Status status, Pageable pageable);

    List<FriendRequest> findByFromUserIdAndStatusOrderByCreatedAtDesc(
        String fromUserId, Status status, Pageable pageable);

    List<FriendRequest> findByFromUserIdOrderByCreatedAtDesc(String fromUserId, Pageable pageable);

    List<FriendRequest> findByIdInAndToUserId(Iterable<Long> ids, String toUserId);

    List<FriendRequest> findByIdInAndFromUserId(Iterable<Long> ids, String fromUserId);
}
