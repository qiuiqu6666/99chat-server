package com.chat99.server.im;

import com.chat99.server.chatattachment.ChatAttachmentSendGuardService;
import com.chat99.server.sticker.StickerSendGuardService;
import com.chat99.server.user.UserFriendService;
import com.chat99.server.user.UserFriendProperties;
import com.chat99.server.user.UserRepository;
import com.chat99.server.wallet.WalletOrderCardSendGuardService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ImC2cBeforeSendMsgCallbackService {

    public static final String CMD_C2C_BEFORE = "C2C.CallbackBeforeSendMsg";
    private static final Logger log = LoggerFactory.getLogger(ImC2cBeforeSendMsgCallbackService.class);

    private final UserFriendProperties friendProps;
    private final UserFriendService friendService;
    private final UserRepository userRepository;
    private final StickerSendGuardService stickerSendGuard;
    private final WalletOrderCardSendGuardService walletOrderCardSendGuard;
    private final ChatAttachmentSendGuardService attachmentSendGuard;
    private final ImCallbackVerifier callbackVerifier;
    private final ObjectMapper json;

    public ImC2cBeforeSendMsgCallbackService(UserFriendProperties friendProps,
                                             UserFriendService friendService,
                                             UserRepository userRepository,
                                             StickerSendGuardService stickerSendGuard,
                                             WalletOrderCardSendGuardService walletOrderCardSendGuard,
                                             ChatAttachmentSendGuardService attachmentSendGuard,
                                             ImCallbackVerifier callbackVerifier,
                                             ObjectMapper json) {
        this.friendProps = friendProps;
        this.friendService = friendService;
        this.userRepository = userRepository;
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
        if (!CMD_C2C_BEFORE.equals(command)) {
            return null;
        }

        Map<String, Object> body = parseBody(rawBody);
        verifyAuth(sdkAppId, callbackToken, sign, requestTime, body);

        var walletReject = walletOrderCardSendGuard.evaluate(body);
        if (walletReject.isPresent()) {
            return ImCallbackVerifier.ImCallbackResponse.reject(walletReject.get());
        }

        var stickerReject = stickerSendGuard.evaluate(body);
        if (stickerReject.isPresent()) {
            return ImCallbackVerifier.ImCallbackResponse.reject(stickerReject.get());
        }

        var attachmentReject = attachmentSendGuard.evaluate(body);
        if (attachmentReject.isPresent()) {
            return ImCallbackVerifier.ImCallbackResponse.reject(attachmentReject.get());
        }

        String from = str(body.get("From_Account"));
        String to = str(body.get("To_Account"));
        if (from == null || to == null || from.isBlank() || to.isBlank() || from.equals(to)) {
            return ImCallbackVerifier.ImCallbackResponse.ok();
        }

        boolean allowed = evaluateAllow(from, to);
        if (friendProps.beforeSendLogOnly()) {
            log.info("c2c beforeSendMsg from={} to={} allowed={} enforce={}",
                from, to, allowed, friendProps.beforeSendEnforce());
            if (!friendProps.beforeSendEnforce()) {
                return ImCallbackVerifier.ImCallbackResponse.ok();
            }
        }

        if (!allowed) {
            return ImCallbackVerifier.ImCallbackResponse.reject("NOT_FRIEND");
        }
        return ImCallbackVerifier.ImCallbackResponse.ok();
    }

    private boolean evaluateAllow(String from, String to) {
        if (!isActiveUser(from)) {
            return false;
        }
        if (!userRepository.findByUserId(to).isPresent()) {
            return false;
        }
        return friendService.isMutualActive(from, to);
    }

    private boolean isActiveUser(String userId) {
        return userRepository.findByUserId(userId)
            .filter(u -> u.getStatus() == 1)
            .isPresent();
    }

    private void verifyAuth(String sdkAppId, String callbackToken, String sign, String requestTime,
                            Map<String, Object> body) {
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

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
