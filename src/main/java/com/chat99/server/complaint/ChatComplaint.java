/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.complaint;

import com.chat99.server.complaint.ChatComplaintType;
import com.chat99.server.complaint.ComplaintReason;
import com.chat99.server.feedback.FeedbackStatus;
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
import java.time.Instant;
import lombok.Generated;

@Entity
@Table(name="chat_complaint", indexes={@Index(name="idx_complaint_reporter_time", columnList="reporter_user_id,created_at"), @Index(name="idx_complaint_reported_time", columnList="reported_user_id,created_at"), @Index(name="idx_complaint_group_time", columnList="group_id,created_at"), @Index(name="idx_complaint_status_time", columnList="status,created_at")})
public class ChatComplaint {
    @Id
    @GeneratedValue(strategy=GenerationType.IDENTITY)
    private Long id;
    @Column(name="reporter_user_id", nullable=false, length=10)
    private String reporterUserId;
    @Enumerated(value=EnumType.STRING)
    @Column(name="chat_type", nullable=false, length=8)
    private ChatComplaintType chatType;
    @Column(name="reported_user_id", nullable=false, length=10)
    private String reportedUserId;
    @Column(name="group_id", length=64)
    private String groupId;
    @Enumerated(value=EnumType.STRING)
    @Column(name="reason", nullable=false, length=32)
    private ComplaintReason reason;
    @Column(name="content", columnDefinition="TEXT")
    private String content;
    @Column(name="msg_key", length=128)
    private String msgKey;
    @Column(name="msg_seq")
    private Long msgSeq;
    @Column(name="screenshot_urls", columnDefinition="TEXT")
    private String screenshotUrlsJson;
    @Column(name="client_version", length=64)
    private String clientVersion;
    @Enumerated(value=EnumType.STRING)
    @Column(name="status", nullable=false, length=16)
    private FeedbackStatus status = FeedbackStatus.PENDING;
    @Column(name="admin_reply", columnDefinition="TEXT")
    private String adminReply;
    @Column(name="updated_at")
    private Instant updatedAt;
    @Column(name="created_at", nullable=false, updatable=false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
        if (this.status == null) {
            this.status = FeedbackStatus.PENDING;
        }
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    @Generated
    public Long getId() {
        return this.id;
    }

    @Generated
    public String getReporterUserId() {
        return this.reporterUserId;
    }

    @Generated
    public ChatComplaintType getChatType() {
        return this.chatType;
    }

    @Generated
    public String getReportedUserId() {
        return this.reportedUserId;
    }

    @Generated
    public String getGroupId() {
        return this.groupId;
    }

    @Generated
    public ComplaintReason getReason() {
        return this.reason;
    }

    @Generated
    public String getContent() {
        return this.content;
    }

    @Generated
    public String getMsgKey() {
        return this.msgKey;
    }

    @Generated
    public Long getMsgSeq() {
        return this.msgSeq;
    }

    @Generated
    public String getScreenshotUrlsJson() {
        return this.screenshotUrlsJson;
    }

    @Generated
    public String getClientVersion() {
        return this.clientVersion;
    }

    @Generated
    public FeedbackStatus getStatus() {
        return this.status;
    }

    @Generated
    public String getAdminReply() {
        return this.adminReply;
    }

    @Generated
    public Instant getUpdatedAt() {
        return this.updatedAt;
    }

    @Generated
    public Instant getCreatedAt() {
        return this.createdAt;
    }

    @Generated
    public void setId(Long id) {
        this.id = id;
    }

    @Generated
    public void setReporterUserId(String reporterUserId) {
        this.reporterUserId = reporterUserId;
    }

    @Generated
    public void setChatType(ChatComplaintType chatType) {
        this.chatType = chatType;
    }

    @Generated
    public void setReportedUserId(String reportedUserId) {
        this.reportedUserId = reportedUserId;
    }

    @Generated
    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    @Generated
    public void setReason(ComplaintReason reason) {
        this.reason = reason;
    }

    @Generated
    public void setContent(String content) {
        this.content = content;
    }

    @Generated
    public void setMsgKey(String msgKey) {
        this.msgKey = msgKey;
    }

    @Generated
    public void setMsgSeq(Long msgSeq) {
        this.msgSeq = msgSeq;
    }

    @Generated
    public void setScreenshotUrlsJson(String screenshotUrlsJson) {
        this.screenshotUrlsJson = screenshotUrlsJson;
    }

    @Generated
    public void setClientVersion(String clientVersion) {
        this.clientVersion = clientVersion;
    }

    @Generated
    public void setStatus(FeedbackStatus status) {
        this.status = status;
    }

    @Generated
    public void setAdminReply(String adminReply) {
        this.adminReply = adminReply;
    }

    @Generated
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Generated
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Generated
    public ChatComplaint() {
    }
}
