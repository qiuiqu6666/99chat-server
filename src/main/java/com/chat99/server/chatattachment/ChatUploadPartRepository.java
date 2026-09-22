package com.chat99.server.chatattachment;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatUploadPartRepository extends JpaRepository<ChatUploadPart, ChatUploadPartId> {

    List<ChatUploadPart> findByUploadIdOrderByPartNumberAsc(String uploadId);

    List<ChatUploadPart> findByUploadIdAndPartNumberGreaterThanOrderByPartNumberAsc(
        String uploadId, int afterPartNumber, Pageable pageable);

    long countByUploadId(String uploadId);

    void deleteByUploadId(String uploadId);
}
