package com.chat99.server.chatattachment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "chat_upload_part")
@IdClass(ChatUploadPartId.class)
@Getter
@Setter
@NoArgsConstructor
public class ChatUploadPart {

    @Id
    @Column(name = "upload_id", nullable = false, length = 48)
    private String uploadId;

    @Id
    @Column(name = "part_number", nullable = false)
    private int partNumber;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "etag", length = 128)
    private String etag;

    @Column(name = "confirmed_at", nullable = false)
    private Instant confirmedAt;
}
