package com.chat99.server.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "wallet_red_packet_claim_notice",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_notice_id", columnNames = "notice_id"),
        @UniqueConstraint(name = "uk_notice_packet_claimer", columnNames = {"packet_id", "claimer_user_id"})
    })
@Getter
@Setter
@NoArgsConstructor
public class WalletRedPacketClaimNotice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "notice_id", nullable = false, length = 128)
    private String noticeId;

    @Column(name = "packet_id", nullable = false)
    private long packetId;

    @Column(name = "sender_user_id", nullable = false, length = 10)
    private String senderUserId;

    @Column(name = "group_id", nullable = false, length = 64)
    private String groupId;

    @Column(name = "claimer_user_id", nullable = false, length = 10)
    private String claimerUserId;

    @Column(name = "show_finished_suffix", nullable = false)
    private boolean showFinishedSuffix;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
