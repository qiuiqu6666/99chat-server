package com.chat99.server.push;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_conversation_pin")
@IdClass(UserConversationPinId.class)
public class UserConversationPin {

    @Id
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Id
    @Column(name = "chat_type", nullable = false, length = 16)
    private String chatType;

    @Id
    @Column(name = "peer_id", nullable = false, length = 128)
    private String peerId;

    @Column(name = "pinned_at", nullable = false)
    private long pinnedAt;

    @Column(name = "updated_at", nullable = false)
    private long updatedAt;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getChatType() {
        return chatType;
    }

    public void setChatType(String chatType) {
        this.chatType = chatType;
    }

    public String getPeerId() {
        return peerId;
    }

    public void setPeerId(String peerId) {
        this.peerId = peerId;
    }

    public long getPinnedAt() {
        return pinnedAt;
    }

    public void setPinnedAt(long pinnedAt) {
        this.pinnedAt = pinnedAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
