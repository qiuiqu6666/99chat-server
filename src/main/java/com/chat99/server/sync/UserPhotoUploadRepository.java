package com.chat99.server.sync;

import com.chat99.server.sync.SyncEnums.UploadStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPhotoUploadRepository extends JpaRepository<UserPhotoUpload, Long> {

    Optional<UserPhotoUpload> findByUploadUuidAndUserId(String uploadUuid, String userId);

    Optional<UserPhotoUpload> findByUploadUuidAndUserIdAndStatus(String uploadUuid, String userId, UploadStatus status);
}
