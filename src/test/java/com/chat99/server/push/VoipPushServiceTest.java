package com.chat99.server.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.im.ImPushDedupStore;
import com.chat99.server.user.UserNotificationSettingsService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VoipPushServiceTest {

    @Mock private PushConfigService pushConfig;
    @Mock private UserPushTokenRepository tokenRepository;
    @Mock private PushTokenService pushTokenService;
    @Mock private ApnsVoipPushSender voipPushSender;
    @Mock private PushAvatarResolver pushAvatarResolver;
    @Mock private PushDisplayNameResolver pushDisplayNameResolver;
    @Mock private ImPushDedupStore dedupStore;
    @Mock private UserNotificationSettingsService notificationSettings;

    private VoipPushService service;

    @BeforeEach
    void setUp() {
        service = new VoipPushService(
            pushConfig, tokenRepository, pushTokenService, voipPushSender,
            pushAvatarResolver, pushDisplayNameResolver, dedupStore, notificationSettings);
    }

    @Test
    void sendIncomingCallNormalizesCompositeIdsAndResolvesCallerName() {
        when(pushConfig.isPushEnabled()).thenReturn(true);
        when(pushConfig.isVoipPushEnabled()).thenReturn(true);
        when(voipPushSender.isReady()).thenReturn(true);
        when(notificationSettings.isCallNotificationEnabled("rqwm8onw3j")).thenReturn(true);
        when(dedupStore.markIfNew("voip|invite-1|rqwm8onw3j")).thenReturn(true);

        UserPushToken token = new UserPushToken();
        token.setId(1L);
        token.setUserId("rqwm8onw3j");
        token.setDeviceId("device-1");
        token.setVoipPushToken("a".repeat(64));
        token.setVoipEnabled(true);
        when(tokenRepository.findByUserIdAndVoipEnabledTrue("rqwm8onw3j")).thenReturn(List.of(token));

        when(pushDisplayNameResolver.resolveCallerDisplayName("rqwm8onw3j", "acnj6oxey9"))
            .thenReturn("张三");
        when(voipPushSender.send(eq(token), any(VoipCallPush.class))).thenReturn(PushSendResult.ok());

        service.sendIncomingCall(new VoipCallPush(
            "invite-1",
            "acnj6oxey9#0#0#rqwm8onw3j",
            "rqwm8onw3j",
            "audio",
            "room-1"));

        ArgumentCaptor<VoipCallPush> captor = ArgumentCaptor.forClass(VoipCallPush.class);
        verify(voipPushSender).send(eq(token), captor.capture());
        VoipCallPush enriched = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("acnj6oxey9", enriched.callerId());
        org.junit.jupiter.api.Assertions.assertEquals("rqwm8onw3j", enriched.calleeId());
        org.junit.jupiter.api.Assertions.assertEquals("张三", enriched.callerName());
    }

    @Test
    void sendIncomingCallUsesVoipTokenWhenNormalPushDisabled() {
        when(pushConfig.isPushEnabled()).thenReturn(true);
        when(pushConfig.isVoipPushEnabled()).thenReturn(true);
        when(voipPushSender.isReady()).thenReturn(true);
        when(notificationSettings.isCallNotificationEnabled("acnj6oxey9")).thenReturn(true);
        when(dedupStore.markIfNew("voip|invite-2|acnj6oxey9")).thenReturn(true);

        UserPushToken token = new UserPushToken();
        token.setId(2L);
        token.setUserId("acnj6oxey9");
        token.setDeviceId("device-2");
        token.setApnsEnabled(false);
        token.setVoipEnabled(true);
        token.setVoipPushToken("d".repeat(64));
        when(tokenRepository.findByUserIdAndVoipEnabledTrue("acnj6oxey9")).thenReturn(List.of(token));
        when(pushDisplayNameResolver.resolveCallerDisplayName("acnj6oxey9", "rqwm8onw3j"))
            .thenReturn("测试");
        when(voipPushSender.send(eq(token), any(VoipCallPush.class))).thenReturn(PushSendResult.ok());

        service.sendIncomingCall(new VoipCallPush(
            "invite-2", "rqwm8onw3j", "acnj6oxey9", "video", "room-2"));

        verify(voipPushSender).send(eq(token), any(VoipCallPush.class));
    }

    @Test
    void sendIncomingCallPushesOnlyLatestSeenVoipDevice() {
        when(pushConfig.isPushEnabled()).thenReturn(true);
        when(pushConfig.isVoipPushEnabled()).thenReturn(true);
        when(voipPushSender.isReady()).thenReturn(true);
        when(notificationSettings.isCallNotificationEnabled("hnyzbsbhw5")).thenReturn(true);
        when(dedupStore.markIfNew("voip|invite-3|hnyzbsbhw5")).thenReturn(true);

        UserPushToken stale = new UserPushToken();
        stale.setId(10L);
        stale.setUserId("hnyzbsbhw5");
        stale.setDeviceId("stale-device");
        stale.setVoipEnabled(true);
        stale.setVoipPushToken("e".repeat(64));
        stale.setLastSeenAt(Instant.parse("2026-08-06T19:16:04Z"));

        UserPushToken fresh = new UserPushToken();
        fresh.setId(11L);
        fresh.setUserId("hnyzbsbhw5");
        fresh.setDeviceId("fresh-device");
        fresh.setVoipEnabled(true);
        fresh.setVoipPushToken("f".repeat(64));
        fresh.setLastSeenAt(Instant.parse("2026-08-13T06:04:20Z"));

        when(tokenRepository.findByUserIdAndVoipEnabledTrue("hnyzbsbhw5"))
            .thenReturn(List.of(stale, fresh));
        when(pushDisplayNameResolver.resolveCallerDisplayName("hnyzbsbhw5", "q14gkm5swv"))
            .thenReturn("阿伦");
        when(voipPushSender.send(eq(fresh), any(VoipCallPush.class))).thenReturn(PushSendResult.ok());

        service.sendIncomingCall(new VoipCallPush(
            "invite-3", "q14gkm5swv", "hnyzbsbhw5", "audio", "room-3"));

        verify(voipPushSender).send(eq(fresh), any(VoipCallPush.class));
        verify(voipPushSender, never()).send(eq(stale), any(VoipCallPush.class));
    }

    @Test
    void sendIncomingCallClearsVoipTokenOnBadDeviceToken() {
        when(pushConfig.isPushEnabled()).thenReturn(true);
        when(pushConfig.isVoipPushEnabled()).thenReturn(true);
        when(voipPushSender.isReady()).thenReturn(true);
        when(notificationSettings.isCallNotificationEnabled("q14gkm5swv")).thenReturn(true);
        when(dedupStore.markIfNew("voip|invite-4|q14gkm5swv")).thenReturn(true);

        UserPushToken token = new UserPushToken();
        token.setId(20L);
        token.setUserId("q14gkm5swv");
        token.setDeviceId("bad-device");
        token.setVoipEnabled(true);
        // 64-char hex so format check passes and APNs path runs
        token.setVoipPushToken("a".repeat(64));
        token.setLastSeenAt(Instant.parse("2026-08-13T07:58:01Z"));
        when(tokenRepository.findByUserIdAndVoipEnabledTrue("q14gkm5swv")).thenReturn(List.of(token));
        when(pushDisplayNameResolver.resolveCallerDisplayName("q14gkm5swv", "hnyzbsbhw5"))
            .thenReturn("熊猫");
        when(voipPushSender.send(eq(token), any(VoipCallPush.class)))
            .thenReturn(PushSendResult.invalidToken("BadDeviceToken"));

        service.sendIncomingCall(new VoipCallPush(
            "invite-4", "hnyzbsbhw5", "q14gkm5swv", "audio", "room-4"));

        verify(pushTokenService).clearVoipToken(20L);
    }

    @Test
    void sendIncomingCallFallsBackAfterInvalidPreferredToken() {
        when(pushConfig.isPushEnabled()).thenReturn(true);
        when(pushConfig.isVoipPushEnabled()).thenReturn(true);
        when(voipPushSender.isReady()).thenReturn(true);
        when(notificationSettings.isCallNotificationEnabled("hnyzbsbhw5")).thenReturn(true);
        when(dedupStore.markIfNew("voip|invite-5|hnyzbsbhw5")).thenReturn(true);

        UserPushToken bad = new UserPushToken();
        bad.setId(30L);
        bad.setUserId("hnyzbsbhw5");
        bad.setDeviceId("bad-latest");
        bad.setVoipEnabled(true);
        bad.setVoipPushToken("b".repeat(64));
        bad.setLastSeenAt(Instant.parse("2026-08-13T10:00:00Z"));

        UserPushToken good = new UserPushToken();
        good.setId(31L);
        good.setUserId("hnyzbsbhw5");
        good.setDeviceId("good-older");
        good.setVoipEnabled(true);
        good.setVoipPushToken("c".repeat(64));
        good.setLastSeenAt(Instant.parse("2026-08-12T10:00:00Z"));

        when(tokenRepository.findByUserIdAndVoipEnabledTrue("hnyzbsbhw5"))
            .thenReturn(List.of(bad, good));
        when(pushDisplayNameResolver.resolveCallerDisplayName("hnyzbsbhw5", "q14gkm5swv"))
            .thenReturn("阿伦");
        when(voipPushSender.send(eq(bad), any(VoipCallPush.class)))
            .thenReturn(PushSendResult.invalidToken("DeviceTokenNotForTopic"));
        when(voipPushSender.send(eq(good), any(VoipCallPush.class)))
            .thenReturn(PushSendResult.ok());

        service.sendIncomingCall(new VoipCallPush(
            "invite-5", "q14gkm5swv", "hnyzbsbhw5", "audio", "room-5"));

        verify(pushTokenService).clearVoipToken(30L);
        verify(voipPushSender).send(eq(good), any(VoipCallPush.class));
    }

    @Test
    void sendIncomingCallClearsImplausibleTokenWithoutApns() {
        when(pushConfig.isPushEnabled()).thenReturn(true);
        when(pushConfig.isVoipPushEnabled()).thenReturn(true);
        when(voipPushSender.isReady()).thenReturn(true);
        when(notificationSettings.isCallNotificationEnabled("acnj6oxey9")).thenReturn(true);
        when(dedupStore.markIfNew("voip|invite-6|acnj6oxey9")).thenReturn(true);

        UserPushToken junk = new UserPushToken();
        junk.setId(40L);
        junk.setUserId("acnj6oxey9");
        junk.setDeviceId("junk");
        junk.setVoipEnabled(true);
        junk.setVoipPushToken("not-hex!!!");
        when(tokenRepository.findByUserIdAndVoipEnabledTrue("acnj6oxey9")).thenReturn(List.of(junk));

        service.sendIncomingCall(new VoipCallPush(
            "invite-6", "rqwm8onw3j", "acnj6oxey9", "audio", "room-6"));

        verify(pushTokenService).clearVoipToken(40L);
        verify(voipPushSender, never()).send(any(), any());
    }

    @Test
    void notifyCalleeDevicesEndedPushesAllVoipDevices() {
        UserPushToken a = new UserPushToken();
        a.setId(51L);
        a.setUserId("callee1");
        a.setDeviceId("phone-a");
        a.setVoipPushToken("a".repeat(64));
        a.setLastSeenAt(Instant.parse("2026-08-01T00:00:00Z"));
        UserPushToken b = new UserPushToken();
        b.setId(52L);
        b.setUserId("callee1");
        b.setDeviceId("phone-b");
        b.setVoipPushToken("b".repeat(64));
        b.setLastSeenAt(Instant.parse("2026-08-13T00:00:00Z"));
        when(pushConfig.isPushEnabled()).thenReturn(true);
        when(pushConfig.isVoipPushEnabled()).thenReturn(true);
        when(voipPushSender.isReady()).thenReturn(true);
        when(dedupStore.markIfNew(anyString())).thenReturn(true);
        when(tokenRepository.findByUserIdAndVoipEnabledTrue("callee1")).thenReturn(List.of(a, b));
        when(voipPushSender.send(any(), any(VoipCallPush.class))).thenReturn(PushSendResult.ok());

        service.notifyCalleeDevicesEnded(
            "call-end-1", "caller1", "callee1", "audio", "call_x", "lk_call", "answered_elsewhere");

        ArgumentCaptor<VoipCallPush> captor = ArgumentCaptor.forClass(VoipCallPush.class);
        verify(voipPushSender, times(2)).send(any(), captor.capture());
        assertThat(captor.getAllValues()).allMatch(VoipCallPush::isTerminal);
        assertThat(captor.getAllValues()).allMatch(p -> "answered_elsewhere".equals(p.action()));
    }

    @Test
    void selectPreferredVoipDevicePicksNewestLastSeen() {
        UserPushToken older = new UserPushToken();
        older.setId(1L);
        older.setDeviceId("a");
        older.setLastSeenAt(Instant.parse("2026-08-01T00:00:00Z"));
        UserPushToken newer = new UserPushToken();
        newer.setId(2L);
        newer.setDeviceId("b");
        newer.setLastSeenAt(Instant.parse("2026-08-13T00:00:00Z"));

        UserPushToken preferred = VoipPushService.selectPreferredVoipDevice(List.of(older, newer));
        org.junit.jupiter.api.Assertions.assertEquals("b", preferred.getDeviceId());
    }

    @Test
    void isPlausibleVoipTokenRejectsShortOrNonHex() {
        org.junit.jupiter.api.Assertions.assertFalse(VoipPushService.isPlausibleVoipToken("abc"));
        org.junit.jupiter.api.Assertions.assertFalse(VoipPushService.isPlausibleVoipToken("g".repeat(64)));
        org.junit.jupiter.api.Assertions.assertTrue(VoipPushService.isPlausibleVoipToken("a".repeat(64)));
    }
}
