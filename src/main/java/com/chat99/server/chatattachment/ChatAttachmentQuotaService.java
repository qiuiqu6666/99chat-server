package com.chat99.server.chatattachment;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChatAttachmentQuotaService {

    private static final Set<ChatUploadStatus> ACTIVE = EnumSet.of(
        ChatUploadStatus.initiated, ChatUploadStatus.uploading, ChatUploadStatus.completing);

    private final ChatAttachmentProperties props;
    private final ChatUserQuotaRepository quotaRepository;
    private final ChatUserDailyQuotaRepository dailyRepository;
    private final ChatUploadSessionRepository sessionRepository;

    public ChatAttachmentQuotaService(ChatAttachmentProperties props,
                                      ChatUserQuotaRepository quotaRepository,
                                      ChatUserDailyQuotaRepository dailyRepository,
                                      ChatUploadSessionRepository sessionRepository) {
        this.props = props;
        this.quotaRepository = quotaRepository;
        this.dailyRepository = dailyRepository;
        this.sessionRepository = sessionRepository;
    }

    public LocalDate quotaDay() {
        return LocalDate.now(ZoneId.of(props.quotaDayTimezone()));
    }

    @Transactional
    public void reserve(String userId, long bytes, LocalDate quotaDay, boolean countSessionSlot) {
        if (bytes < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (countSessionSlot) {
            long active = sessionRepository.countByOwnerUserIdAndParentUploadIdIsNullAndStatusInAndExpiresAtAfter(
                userId, ACTIVE, Instant.now());
            if (active >= props.maxActiveUploadsPerUser()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "QUOTA_EXCEEDED");
            }
        }
        ChatUserQuota quota = quotaRepository.findByUserIdForUpdate(userId).orElseGet(() -> {
            ChatUserQuota created = new ChatUserQuota();
            created.setUserId(userId);
            created.setUsedStorageBytes(0);
            created.setReservedStorageBytes(0);
            return quotaRepository.save(created);
        });
        quota = quotaRepository.findByUserIdForUpdate(userId).orElse(quota);
        if (quota.getUsedStorageBytes() + quota.getReservedStorageBytes() + bytes > props.userStorageQuotaBytes()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "QUOTA_EXCEEDED");
        }
        quota.setReservedStorageBytes(quota.getReservedStorageBytes() + bytes);
        quotaRepository.save(quota);

        ChatUserDailyQuota daily = dailyRepository.findForUpdate(userId, quotaDay).orElseGet(() -> {
            ChatUserDailyQuota created = new ChatUserDailyQuota();
            created.setUserId(userId);
            created.setQuotaDay(quotaDay);
            created.setUsedBytes(0);
            created.setReservedBytes(0);
            return dailyRepository.save(created);
        });
        daily = dailyRepository.findForUpdate(userId, quotaDay).orElse(daily);
        if (daily.getUsedBytes() + daily.getReservedBytes() + bytes > props.dailyUploadQuotaBytes()) {
            quota.setReservedStorageBytes(quota.getReservedStorageBytes() - bytes);
            quotaRepository.save(quota);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "QUOTA_EXCEEDED");
        }
        daily.setReservedBytes(daily.getReservedBytes() + bytes);
        dailyRepository.save(daily);
    }

    @Transactional
    public void settle(String userId, LocalDate quotaDay, long reservedBytes, long actualBytes) {
        if (actualBytes > reservedBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE");
        }
        ChatUserQuota quota = requireQuota(userId);
        quota.setReservedStorageBytes(Math.max(0, quota.getReservedStorageBytes() - reservedBytes));
        quota.setUsedStorageBytes(quota.getUsedStorageBytes() + actualBytes);
        quotaRepository.save(quota);

        ChatUserDailyQuota daily = requireDaily(userId, quotaDay);
        daily.setReservedBytes(Math.max(0, daily.getReservedBytes() - reservedBytes));
        daily.setUsedBytes(daily.getUsedBytes() + actualBytes);
        dailyRepository.save(daily);
    }

    @Transactional
    public void releaseReserved(String userId, LocalDate quotaDay, long reservedBytes) {
        ChatUserQuota quota = quotaRepository.findByUserIdForUpdate(userId).orElse(null);
        if (quota != null) {
            quota.setReservedStorageBytes(Math.max(0, quota.getReservedStorageBytes() - reservedBytes));
            quotaRepository.save(quota);
        }
        ChatUserDailyQuota daily = dailyRepository.findForUpdate(userId, quotaDay).orElse(null);
        if (daily != null) {
            daily.setReservedBytes(Math.max(0, daily.getReservedBytes() - reservedBytes));
            dailyRepository.save(daily);
        }
    }

    @Transactional
    public void releaseUsed(String userId, long usedBytes) {
        ChatUserQuota quota = quotaRepository.findByUserIdForUpdate(userId).orElse(null);
        if (quota == null) {
            return;
        }
        quota.setUsedStorageBytes(Math.max(0, quota.getUsedStorageBytes() - usedBytes));
        quotaRepository.save(quota);
    }

    private ChatUserQuota requireQuota(String userId) {
        return quotaRepository.findByUserIdForUpdate(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "QUOTA_EXCEEDED"));
    }

    private ChatUserDailyQuota requireDaily(String userId, LocalDate quotaDay) {
        return dailyRepository.findForUpdate(userId, quotaDay)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "QUOTA_EXCEEDED"));
    }
}
