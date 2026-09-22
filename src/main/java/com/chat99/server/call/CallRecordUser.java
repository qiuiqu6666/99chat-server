package com.chat99.server.call;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "call_record_user", uniqueConstraints = {
    @UniqueConstraint(name = "uk_call_record_user", columnNames = {"call_id", "user_id"})
}, indexes = {
    @Index(name = "idx_call_record_user_list", columnList = "user_id,is_deleted,occurred_at"),
    @Index(name = "idx_call_record_user_missed", columnList = "user_id,result,is_deleted,occurred_at")
})
@Getter
@Setter
@NoArgsConstructor
public class CallRecordUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "call_id", nullable = false, length = 128)
    private String callId;

    @Column(name = "user_id", nullable = false, length = 10)
    private String userId;

    @Column(name = "peer_user_id", length = 10)
    private String peerUserId;

    @Column(name = "role", length = 16)
    private String role;

    @Column(name = "direction", nullable = false, length = 16)
    private String direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 16)
    private CallRecordResult result;

    @Column(name = "duration_sec", nullable = false)
    private int durationSec;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
