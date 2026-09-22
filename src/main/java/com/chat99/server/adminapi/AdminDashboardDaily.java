package com.chat99.server.adminapi;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 按自然日（Asia/Shanghai）聚合的可增量指标：消息数、新建群等。 */
@Entity
@Table(name = "admin_dashboard_daily")
@Getter
@Setter
@NoArgsConstructor
public class AdminDashboardDaily {

    @Id
    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    @Column(name = "c2c_message_count", nullable = false)
    private long c2cMessageCount;

    @Column(name = "group_message_count", nullable = false)
    private long groupMessageCount;

    @Column(name = "groups_created_count", nullable = false)
    private long groupsCreatedCount;

    @Column(name = "system_error_count", nullable = false)
    private long systemErrorCount;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
