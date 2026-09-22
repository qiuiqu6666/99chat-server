package com.chat99.server.push;

import java.io.Serializable;
import java.util.Objects;

public class UserConversationPinId implements Serializable {

    private String userId;
    private String chatType;
    private String peerId;

    public UserConversationPinId() {}

    public UserConversationPinId(String userId, String chatType, String peerId) {
        this.userId = userId;
        this.chatType = chatType;
        this.peerId = peerId;
    }

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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserConversationPinId that)) {
            return false;
        }
        return Objects.equals(userId, that.userId)
            && Objects.equals(chatType, that.chatType)
            && Objects.equals(peerId, that.peerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, chatType, peerId);
    }
}
