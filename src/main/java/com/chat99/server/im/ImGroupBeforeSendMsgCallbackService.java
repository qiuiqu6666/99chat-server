package com.chat99.server.im;

import com.chat99.server.chatattachment.ChatAttachmentSendGuardService;
import com.chat99.server.group.GroupMuteAllSendGuardService;
import com.chat99.server.sticker.StickerSendGuardService;
import com.chat99.server.wallet.WalletOrderCardSendGuardService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ImGroupBeforeSendMsgCallbackService {

    public static final String CMD_GROUP_BEFORE = "Group.CallbackBeforeSendMsg";

    private final GroupMuteAllSendGuardService muteAllSendGuard;
    private final StickerSendGuardService stickerSendGuard;
    private final WalletOrderCardSendGuardService walletOrderCardSendGuard;
    private final ChatAttachmentSendGuardService attachmentSendGuard;
    private final ImCallbackVerifier callbackVerifier;
    private final ObjectMapper json;

    public ImGroupBeforeSendMsgCallbackService(GroupMuteAllSendGuardService muteAllSendGuard,
                                               StickerSendGuardService stickerSendGuard,
                                               WalletOrderCardSendGuardService walletOrderCardSendGuard,
                                               ChatAttachmentSendGuardService attachmentSendGuard,
                                               ImCallbackVerifier callbackVerifier,
                                               ObjectMapper json) {
        this.muteAllSendGuard = muteAllSendGuard;
        this.stickerSendGuard = stickerSendGuard;
        this.walletOrderCardSendGuard = walletOrderCardSendGuard;
        this.attachmentSendGuard = attachmentSendGuard;
        this.callbackVerifier = callbackVerifier;
        this.json = json;
    }

    public ImCallbackVerifier.ImCallbackResponse handle(String sdkAppId,
                                                        String command,
                                                        String callbackToken,
                                                        String sign,
                                                        String requestTime,
                                                        String rawBody) {
        if (!CMD_GROUP_BEFORE.equals(command)) {
            return null;
        }

        Map<String, Object> body = parseBody(rawBody);
        verifyAuth(callbackToken, sign, requestTime);

        Optional<String> muteReject = muteAllSendGuard.evaluate(body);
        if (muteReject.isPresent()) {
            return ImCallbackVerifier.ImCallbackResponse.reject(muteReject.get());
        }

        boolean stickerOn = stickerSendGuard.isEnabled();
        boolean walletOn = walletOrderCardSendGuard.isEnabled();

        if (walletOn) {
            Optional<String> walletReject = walletOrderCardSendGuard.evaluate(body);
            if (walletReject.isPresent()) {
                return ImCallbackVerifier.ImCallbackResponse.reject(walletReject.get());
            }
        }

        if (stickerOn) {
            Optional<String> stickerReject = stickerSendGuard.evaluate(body);
            if (stickerReject.isPresent()) {
                return ImCallbackVerifier.ImCallbackResponse.reject(stickerReject.get());
            }
        }
        Optional<String> attachmentReject = attachmentSendGuard.evaluate(body);
        if (attachmentReject.isPresent()) {
            return ImCallbackVerifier.ImCallbackResponse.reject(attachmentReject.get());
        }
        return ImCallbackVerifier.ImCallbackResponse.ok();
    }

    private void verifyAuth(String callbackToken, String sign, String requestTime) {
        if (sign != null && !sign.isBlank()) {
            callbackVerifier.verifySignature(sign, requestTime);
        } else {
            callbackVerifier.verifyQueryToken(callbackToken);
        }
    }

    private Map<String, Object> parseBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(rawBody, new TypeReference<>() {});
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_CALLBACK_BODY");
        }
    }
}
