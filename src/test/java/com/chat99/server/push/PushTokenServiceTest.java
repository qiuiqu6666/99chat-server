package com.chat99.server.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PushTokenServiceTest {

    @Mock private UserPushTokenRepository repository;

    @Test
    void registeringVoipTokenDoesNotReenableInvalidNormalToken() {
        UserPushToken row = new UserPushToken();
        row.setId(1L);
        row.setUserId("user-1");
        row.setDeviceId("device-1");
        row.setPlatform(PushPlatform.IOS);
        row.setApnsEnabled(false);
        when(repository.findByUserIdAndDeviceId("user-1", "device-1"))
            .thenReturn(Optional.of(row));
        when(repository.save(row)).thenReturn(row);

        new PushTokenService(repository)
            .registerVoip("user-1", "device-1", "voip-token");

        ArgumentCaptor<UserPushToken> captor = ArgumentCaptor.forClass(UserPushToken.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().isApnsEnabled()).isFalse();
        assertThat(captor.getValue().isVoipEnabled()).isTrue();
    }

    @Test
    void invalidNormalTokenDoesNotDisableVoipToken() {
        UserPushToken row = new UserPushToken();
        row.setId(2L);
        row.setApnsEnabled(true);
        row.setVoipEnabled(true);
        when(repository.findById(2L)).thenReturn(Optional.of(row));

        new PushTokenService(repository).disableToken(2L);

        assertThat(row.isApnsEnabled()).isFalse();
        assertThat(row.isVoipEnabled()).isTrue();
        verify(repository).save(row);
    }
}
