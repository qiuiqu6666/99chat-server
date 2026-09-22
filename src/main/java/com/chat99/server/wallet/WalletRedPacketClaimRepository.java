package com.chat99.server.wallet;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletRedPacketClaimRepository extends JpaRepository<WalletRedPacketClaim, Long> {

    boolean existsByPacketIdAndUserId(long packetId, String userId);

    Optional<WalletRedPacketClaim> findByPacketIdAndUserId(long packetId, String userId);

    long countByPacketId(long packetId);

    List<WalletRedPacketClaim> findByPacketIdOrderByCreatedAtAsc(long packetId);

    Page<WalletRedPacketClaim> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);
}
