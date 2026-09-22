package com.chat99.server.wallet;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletRedPacketClaimNoticeRepository extends JpaRepository<WalletRedPacketClaimNotice, Long> {

    boolean existsByNoticeId(String noticeId);

    boolean existsByPacketIdAndClaimerUserId(long packetId, String claimerUserId);

    Optional<WalletRedPacketClaimNotice> findByPacketIdAndClaimerUserId(long packetId, String claimerUserId);
}
