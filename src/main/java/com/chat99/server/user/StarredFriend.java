package com.chat99.server.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "user_starred_friend", uniqueConstraints = {
    @UniqueConstraint(name = "uk_user_friend", columnNames = {"user_id", "friend_user_id"})
}, indexes = {
    @Index(name = "idx_starred_user", columnList = "user_id, starred_at")
})
@Getter
@Setter
@NoArgsConstructor
public class StarredFriend {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 10)
    private String userId;

    @Column(name = "friend_user_id", nullable = false, length = 10)
    private String friendUserId;

    @Column(name = "starred_at", nullable = false, updatable = false)
    private Instant starredAt;

    @PrePersist
    void onCreate() {
        if (starredAt == null) {
            starredAt = Instant.now();
        }
    }
}
