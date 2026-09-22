package com.chat99.server.adminapi;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "admin_login_logs", indexes = {
    @Index(name = "idx_admin_login_at", columnList = "login_at"),
    @Index(name = "idx_admin_login_user", columnList = "admin_user_id")
})
@Getter
@Setter
@NoArgsConstructor
public class AdminLoginLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "admin_user_id")
    private Long adminUserId;

    @Column(name = "username_attempted", nullable = false, length = 64)
    private String usernameAttempted;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "ip", length = 45)
    private String ip;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "fail_reason", length = 128)
    private String failReason;

    @Column(name = "login_at", nullable = false, updatable = false)
    private Instant loginAt;

    @PrePersist
    void onCreate() {
        if (loginAt == null) {
            loginAt = Instant.now();
        }
    }
}
