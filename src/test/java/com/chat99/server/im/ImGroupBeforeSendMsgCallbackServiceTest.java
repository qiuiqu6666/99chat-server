package com.chat99.server.im;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.chatattachment.ChatAttachmentSendGuardService;
import com.chat99.server.group.GroupMuteAllSendGuardService;
import com.chat99.server.sticker.StickerSendGuardService;
import com.chat99.server.wallet.WalletOrderCardSendGuardService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImGroupBeforeSendMsgCallbackServiceTest {

    @Mock GroupMuteAllSendGuardService muteAllSendGuard;
    @Mock StickerSendGuardService stickerSendGuard;
    @Mock WalletOrderCardSendGuardService walletOrderCardSendGuard;
    @Mock ChatAttachmentSendGuardService attachmentSendGuard;
    @Mock ImCallbackVerifier callbackVerifier;

    private ImGroupBeforeSendMsgCallbackService service;

    @BeforeEach
    void setUp() {
        service = new ImGroupBeforeSendMsgCallbackService(
            muteAllSendGuard, stickerSendGuard, walletOrderCardSendGuard, attachmentSendGuard,
            callbackVerifier, new ObjectMapper());
        when(muteAllSendGuard.evaluate(any())).thenReturn(Optional.empty());
        when(attachmentSendGuard.evaluate(any())).thenReturn(Optional.empty());
    }

    @Test
    void handle_rejectsUnavailableSticker() {
        when(walletOrderCardSendGuard.isEnabled()).thenReturn(false);
        when(stickerSendGuard.isEnabled()).thenReturn(true);
        when(stickerSendGuard.evaluate(any())).thenReturn(Optional.of("STICKER_UNAVAILABLE"));

        String body = """
            {
              "GroupId": "g1",
              "From_Account": "u1",
              "MsgBody": [
                {
                  "MsgType": "TIMFaceElem",
                  "MsgContent": {
                    "Index": 99,
                    "Data": "99chat://sticker/stk_bad"
                  }
                }
              ]
            }
            """;

        var response = service.handle(
            "123", ImGroupBeforeSendMsgCallbackService.CMD_GROUP_BEFORE,
            "token", null, null, body);

        assertThat(response).isNotNull();
        assertThat(response.ErrorCode()).isEqualTo(1);
        assertThat(response.ErrorInfo()).isEqualTo("STICKER_UNAVAILABLE");
    }

    @Test
    void handle_rejectsWalletCardDup() {
        when(walletOrderCardSendGuard.isEnabled()).thenReturn(true);
        when(stickerSendGuard.isEnabled()).thenReturn(false);
        when(walletOrderCardSendGuard.evaluate(any())).thenReturn(Optional.of("WALLET_CARD_DUP"));

        String body = """
            {
              "GroupId": "m25KMR3N5CY",
              "From_Account": "quu92o5rt5",
              "MsgBody": [
                {
                  "MsgType": "TIMCustomElem",
                  "MsgContent": {
                    "Data": "{\\"businessID\\":\\"wallet_order\\",\\"type\\":\\"wallet_red_packet\\",\\"orderId\\":368}"
                  }
                }
              ]
            }
            """;

        var response = service.handle(
            "123", ImGroupBeforeSendMsgCallbackService.CMD_GROUP_BEFORE,
            "token", null, null, body);

        assertThat(response).isNotNull();
        assertThat(response.ErrorCode()).isEqualTo(1);
        assertThat(response.ErrorInfo()).isEqualTo("WALLET_CARD_DUP");
        verify(stickerSendGuard, never()).evaluate(any());
    }

    @Test
    void handle_rejectsMuteAllEvenWhenOtherGuardsOff() {
        when(stickerSendGuard.isEnabled()).thenReturn(false);
        when(walletOrderCardSendGuard.isEnabled()).thenReturn(false);
        when(muteAllSendGuard.evaluate(any()))
            .thenReturn(Optional.of(GroupMuteAllSendGuardService.REJECT_CODE));

        String body = """
            {
              "GroupId": "g1",
              "From_Account": "u1",
              "MsgBody": [
                {
                  "MsgType": "TIMTextElem",
                  "MsgContent": { "Text": "hi" }
                }
              ]
            }
            """;

        var response = service.handle(
            "123", ImGroupBeforeSendMsgCallbackService.CMD_GROUP_BEFORE,
            "token", null, null, body);

        assertThat(response).isNotNull();
        assertThat(response.ErrorCode()).isEqualTo(1);
        assertThat(response.ErrorInfo()).isEqualTo(GroupMuteAllSendGuardService.REJECT_CODE);
        verify(walletOrderCardSendGuard, never()).evaluate(any());
        verify(stickerSendGuard, never()).evaluate(any());
    }

    @Test
    void handle_okWhenOnlyMuteGuardRunsAndAllows() {
        when(stickerSendGuard.isEnabled()).thenReturn(false);
        when(walletOrderCardSendGuard.isEnabled()).thenReturn(false);

        String body = """
            {
              "GroupId": "g1",
              "From_Account": "u1",
              "MsgBody": [
                {
                  "MsgType": "TIMCustomElem",
                  "MsgContent": {
                    "Data": "{\\"businessID\\":\\"group_tip\\",\\"action\\":\\"member_added\\"}"
                  }
                }
              ]
            }
            """;

        var response = service.handle(
            "123", ImGroupBeforeSendMsgCallbackService.CMD_GROUP_BEFORE,
            "token", null, null, body);

        assertThat(response).isNotNull();
        assertThat(response.ErrorCode()).isEqualTo(0);
        verify(muteAllSendGuard).evaluate(any());
    }

    @Test
    void handle_ignoresOtherCommands() {
        var response = service.handle("123", "Group.CallbackAfterSendMsg", "token", null, null, "{}");
        assertThat(response).isNull();
        verify(muteAllSendGuard, never()).evaluate(any());
        verify(stickerSendGuard, never()).evaluate(any());
        verify(walletOrderCardSendGuard, never()).evaluate(any());
    }
}
