package com.chat99.server.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "friend_contact_change")
@Getter
@Setter
@NoArgsConstructor
public class FriendContactChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "seq", nullable = false, unique = true)
    private long seq;

    @Column(name = "event_id", nullable = false, length = 64, unique = true)
    private String eventId;

    @Column(name = "item_version", nullable = false)
    private long itemVersion;

    @Column(name = "revision", nullable = false)
    private long revision;

    @Column(name = "deleted", nullable = false)
    private boolean deleted;

    @Column(name = "account_id", nullable = false, length = 64)
    private String accountId;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "peer_user_id", nullable = false, length = 64)
    private String peerUserId;

    @Column(name = "payload_json", columnDefinition = "JSON")
    private String payloadJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private long createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt <= 0) {
            createdAt = System.currentTimeMillis();
        }
    }
}
