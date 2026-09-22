package com.chat99.server.official;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "official_accounts", uniqueConstraints = {
    @UniqueConstraint(name = "uk_official_account_id", columnNames = "official_account_id"),
    @UniqueConstraint(name = "uk_slug", columnNames = "slug")
}, indexes = {
    @Index(name = "idx_official_enabled_sort", columnList = "enabled, sort_order")
})
@Getter
@Setter
@NoArgsConstructor
public class OfficialAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "slug", nullable = false, length = 48)
    private String slug;

    @Column(name = "official_account_id", nullable = false, length = 64)
    private String officialAccountId;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "introduction", length = 400)
    private String introduction;

    @Column(name = "face_url", length = 500)
    private String faceUrl;

    @Column(name = "organization", length = 500)
    private String organization;

    @Column(name = "owner_account", nullable = false, length = 64)
    private String ownerAccount;

    @Column(name = "max_subscriber_num", nullable = false)
    private int maxSubscriberNum = 100_000;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
