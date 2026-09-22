package com.chat99.server.sms;

import com.chat99.server.common.ClientContext;
import com.chat99.server.common.PhoneUtils;
import com.chat99.server.user.User;
import com.chat99.server.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/sms")
public class SmsController {

    private final SmsRateLimiter rateLimiter;
    private final SmsCodeStore codeStore;
    private final SmsDeliveryService smsDelivery;
    private final PhoneUtils phoneUtils;
    private final UserRepository userRepository;
    private final ClientContext clientContext;

    public SmsController(SmsRateLimiter rateLimiter, SmsCodeStore codeStore, SmsDeliveryService smsDelivery,
                         PhoneUtils phoneUtils, UserRepository userRepository, ClientContext clientContext) {
        this.rateLimiter = rateLimiter;
        this.codeStore = codeStore;
        this.smsDelivery = smsDelivery;
        this.phoneUtils = phoneUtils;
        this.userRepository = userRepository;
        this.clientContext = clientContext;
    }

    @PostMapping("/send")
    public SendResponse send(@Valid @RequestBody SendRequest req, HttpServletRequest http, Authentication auth) {
        SmsScene scene;
        try {
            scene = SmsScene.valueOf(req.scene());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SCENE_NOT_ALLOWED");
        }
        if (scene == SmsScene.DEVICE && (req.challengeId() == null || req.challengeId().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        String ip = clientContext.ip(http);
        if (req.phone() == null || req.phone().isBlank() || req.phone().contains("*")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PHONE");
        }
        PhoneUtils.Parsed parsed;
        try {
            parsed = phoneUtils.parseE164(req.phone());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PHONE");
        }
        rateLimiter.check(parsed.e164(), ip);
        switch (scene) {
            case REGISTER -> {
                if (userRepository.existsByPhone(parsed.e164())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "PHONE_EXISTS");
                }
                smsDelivery.sendForScene(SmsScene.REGISTER, parsed.e164(), parsed.countryCode(), null);
            }
            case LOGIN -> {
                if (!userRepository.existsByPhone(parsed.e164())) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
                }
                smsDelivery.sendForScene(SmsScene.LOGIN, parsed.e164(), parsed.countryCode(), null);
            }
            case RESET -> {
                if (!userRepository.existsByPhone(parsed.e164())) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
                }
                smsDelivery.sendForScene(SmsScene.RESET, parsed.e164(), parsed.countryCode(), null);
            }
            case PAY_PIN_RESET -> {
                requireAuthenticatedBoundUser(auth, parsed.e164());
                smsDelivery.sendForScene(SmsScene.PAY_PIN_RESET, parsed.e164(), parsed.countryCode(), null);
            }
            case DEVICE -> {
                SmsCodeStore.DeviceChallenge ch = codeStore.readDeviceChallenge(req.challengeId());
                if (ch == null) {
                    throw new ResponseStatusException(HttpStatus.GONE, "CHALLENGE_EXPIRED");
                }
                User deviceUser = userRepository.findByUserId(ch.userId()).orElse(null);
                if (deviceUser == null || deviceUser.getStatus() != 1) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED");
                }
                if (!parsed.e164().equals(ch.phone())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PHONE");
                }
                codeStore.checkDeviceSendLimit(req.challengeId());
                smsDelivery.sendForScene(SmsScene.DEVICE, parsed.e164(), parsed.countryCode(), ch.code());
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SCENE_NOT_ALLOWED");
        }
        return new SendResponse(true);
    }

    private User requireAuthenticatedBoundUser(Authentication auth, String phoneE164) {
        if (auth == null || auth.getPrincipal() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        String userId = (String) auth.getPrincipal();
        User user = userRepository.findByUserId(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
        if (user.getStatus() != 1) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED");
        }
        if (!phoneUtils.hasBoundPhone(user.getPhone())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PHONE_NOT_BOUND");
        }
        if (!phoneE164.equals(user.getPhone())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PHONE");
        }
        return user;
    }

    public record SendRequest(@NotBlank String phone, @NotBlank String scene, String challengeId) {}

    public record SendResponse(boolean ok) {}
}
