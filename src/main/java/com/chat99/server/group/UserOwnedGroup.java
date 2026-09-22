package com.chat99.server.group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "user_owned_group", indexes = {
    @Index(name = "idx_owned_user_type", columnList = "owner_user_id, group_type")
})
@Getter
@Setter
@NoArgsConstructor
public class UserOwnedGroup {

    @Id
    @Column(name = "group_id", nullable = false, length = 128)
    private String groupId;

    @Column(name = "owner_user_id", nullable = false, length = 32)
    private String ownerUserId;

    @Column(name = "group_type", nullable = false, length = 32)
    private String groupType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
