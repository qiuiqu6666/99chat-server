package com.chat99.server.chatattachment;

import java.io.Serializable;
import java.util.Objects;

public class ChatAttachmentDeviceCapabilityId implements Serializable {

    private String userId;
    private String deviceId;

    public ChatAttachmentDeviceCapabilityId() {}

    public ChatAttachmentDeviceCapabilityId(String userId, String deviceId) {
        this.userId = userId;
        this.deviceId = deviceId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ChatAttachmentDeviceCapabilityId that)) {
            return false;
        }
        return Objects.equals(userId, that.userId) && Objects.equals(deviceId, that.deviceId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, deviceId);
    }
}
