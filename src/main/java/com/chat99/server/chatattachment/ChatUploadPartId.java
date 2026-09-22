package com.chat99.server.chatattachment;

import java.io.Serializable;
import java.util.Objects;

public class ChatUploadPartId implements Serializable {

    private String uploadId;
    private int partNumber;

    public ChatUploadPartId() {}

    public ChatUploadPartId(String uploadId, int partNumber) {
        this.uploadId = uploadId;
        this.partNumber = partNumber;
    }

    public String getUploadId() {
        return uploadId;
    }

    public void setUploadId(String uploadId) {
        this.uploadId = uploadId;
    }

    public int getPartNumber() {
        return partNumber;
    }

    public void setPartNumber(int partNumber) {
        this.partNumber = partNumber;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ChatUploadPartId that)) {
            return false;
        }
        return partNumber == that.partNumber && Objects.equals(uploadId, that.uploadId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uploadId, partNumber);
    }
}
