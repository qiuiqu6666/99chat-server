package com.chat99.server.moments;

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
@Table(name = "moment_settings_hidden_author", uniqueConstraints = {
    @UniqueConstraint(name = "uk_moment_hidden_author", columnNames = {"user_id", "hidden_user_id"})
}, indexes = {
    @Index(name = "idx_moment_hidden_author_user", columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
public class MomentSettingsHiddenAuthor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "hidden_user_id", nullable = false, length = 64)
    private String hiddenUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
