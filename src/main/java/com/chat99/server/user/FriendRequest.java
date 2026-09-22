package com.chat99.server.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "friend_request", indexes = {
    @Index(name = "idx_friend_req_to_status", columnList = "to_user_id, status, created_at DESC"),
    @Index(name = "idx_friend_req_from_status", columnList = "from_user_id, status, created_at DESC")
})
@Getter
@Setter
@NoArgsConstructor
public class FriendRequest {

    public enum Status {
        pending,
        accepted,
        rejected
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "from_user_id", nullable = false, length = 64)
    private String fromUserId;

    @Column(name = "to_user_id", nullable = false, length = 64)
    private String toUserId;

    @Column(name = "add_wording", length = 200)
    private String addWording;

    @Enumerated(EnumType.STRING)
    @Column(name = "add_source", nullable = false, length = 20)
    private FriendApplicationHistory.AddSource addSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.pending;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "handled_at")
    private Instant handledAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
