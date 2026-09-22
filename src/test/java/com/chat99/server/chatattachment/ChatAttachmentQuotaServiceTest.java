package com.chat99.server.chatattachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ChatAttachmentQuotaServiceTest {

    @Mock ChatUserQuotaRepository quotaRepository;
    @Mock ChatUserDailyQuotaRepository dailyRepository;
    @Mock ChatUploadSessionRepository sessionRepository;

    private ChatAttachmentQuotaService service;

    @BeforeEach
    void setUp() {
        service = new ChatAttachmentQuotaService(
            ChatAttachmentTestSupport.props(), quotaRepository, dailyRepository, sessionRepository);
    }

    @Test
    void reserveRejectsFourthActiveSession() {
        when(sessionRepository.countByOwnerUserIdAndParentUploadIdIsNullAndStatusInAndExpiresAtAfter(
            eq("u1"), any(), any())).thenReturn(3L);
        assertThatThrownBy(() -> service.reserve("u1", 100, LocalDate.now(), true))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(ex -> ((ResponseStatusException) ex).getReason())
            .isEqualTo("QUOTA_EXCEEDED");
        verify(quotaRepository, never()).save(any());
    }

    @Test
    void settleDoesNotRefundDailyUsed() {
        ChatUserQuota quota = new ChatUserQuota();
        quota.setUserId("u1");
        quota.setUsedStorageBytes(10);
        quota.setReservedStorageBytes(50);
        ChatUserDailyQuota daily = new ChatUserDailyQuota();
        daily.setUserId("u1");
        daily.setQuotaDay(LocalDate.of(2026, 9, 14));
        daily.setUsedBytes(10);
        daily.setReservedBytes(50);
        when(quotaRepository.findByUserIdForUpdate("u1")).thenReturn(Optional.of(quota));
        when(dailyRepository.findForUpdate(eq("u1"), any())).thenReturn(Optional.of(daily));

        service.settle("u1", LocalDate.of(2026, 9, 14), 50, 40);

        assertThat(quota.getUsedStorageBytes()).isEqualTo(50);
        assertThat(quota.getReservedStorageBytes()).isEqualTo(0);
        assertThat(daily.getUsedBytes()).isEqualTo(50);
        assertThat(daily.getReservedBytes()).isEqualTo(0);

        service.releaseUsed("u1", 40);
        assertThat(quota.getUsedStorageBytes()).isEqualTo(10);
        assertThat(daily.getUsedBytes()).isEqualTo(50);
    }
}
