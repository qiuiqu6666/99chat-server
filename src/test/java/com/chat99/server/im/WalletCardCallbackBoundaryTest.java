package com.chat99.server.im;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.chat99.server.wallet.*;
import com.chat99.server.push.PushConfigService;
import com.chat99.server.group.GroupMuteAllSendGuardService;
import com.chat99.server.sticker.StickerSendGuardService;
import com.chat99.server.chatattachment.ChatAttachmentSendGuardService;
import com.chat99.server.user.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.server.ResponseStatusException;

class WalletCardCallbackBoundaryTest {
    final PushConfigService config = mock(PushConfigService.class);
    final ImCallbackVerifier verifier = new ImCallbackVerifier(config);
    final ObjectMapper json = new ObjectMapper();
    final WalletOrderCardSendGuardService guard = new WalletOrderCardSendGuardService(
        new WalletOrderCardGuardProperties(false, false, true, 90, List.of("administrator")),
        mock(WalletRedPacketRepository.class), mock(WalletTransferRepository.class),
        mock(ImUserIdService.class), mock(StringRedisTemplate.class), json);

    @Test void missingSecretFailsClosedForTokenAndSignature() {
        assertThatThrownBy(() -> verifier.verifyQueryToken(null)).isInstanceOfSatisfying(ResponseStatusException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
        assertThatThrownBy(() -> verifier.verifySignature("x", "0")).isInstanceOf(ResponseStatusException.class);
    }
    @Test void validTokenAndFreshSignaturePassButInvalidOrExpiredOnesDoNot() throws Exception {
        when(config.getCallbackToken()).thenReturn("test-secret");
        verifier.verifyQueryToken("test-secret");
        assertThatThrownBy(() -> verifier.verifyQueryToken("wrong")).isInstanceOf(ResponseStatusException.class);
        String time = Long.toString(Instant.now().getEpochSecond());
        String signature = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(("test-secret" + time).getBytes(StandardCharsets.UTF_8)));
        verifier.verifySignature(signature, time);
        assertThatThrownBy(() -> verifier.verifySignature("wrong", time)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> verifier.verifySignature(signature, "1")).isInstanceOf(ResponseStatusException.class);
    }
    @Test void realC2cAndGroupCallbackHandlersRejectWalletBeforeOtherMessageGuards() throws Exception {
        when(config.getCallbackToken()).thenReturn("test-secret");
        String body = json.writeValueAsString(Map.of("From_Account", "administrator", "To_Account", "u2", "GroupId", "g1",
            "MsgBody", List.of(Map.of("MsgType", "TIMCustomElem", "MsgContent", Map.of("Data",
                "{\"customType\":\"text\",\"type\":\"wallet_red_packet\",\"orderId\":\"copied\"}")))));
        var stickers = mock(StickerSendGuardService.class);
        var attachments = mock(ChatAttachmentSendGuardService.class);
        var c2c = new ImC2cBeforeSendMsgCallbackService(mock(UserFriendProperties.class), mock(UserFriendService.class),
            mock(UserRepository.class), stickers, guard, attachments, verifier, json);
        var group = new ImGroupBeforeSendMsgCallbackService(mock(GroupMuteAllSendGuardService.class),
            stickers, guard, attachments, verifier, json);
        assertThat(c2c.handle("1", ImC2cBeforeSendMsgCallbackService.CMD_C2C_BEFORE,
            "test-secret", null, null, body).ErrorCode()).isNotZero();
        assertThat(group.handle("1", ImGroupBeforeSendMsgCallbackService.CMD_GROUP_BEFORE,
            "test-secret", null, null, body).ErrorCode()).isNotZero();
        verifyNoInteractions(attachments);
    }
}
