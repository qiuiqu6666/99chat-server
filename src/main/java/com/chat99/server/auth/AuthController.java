package com.chat99.server.auth;

import com.chat99.server.adminapi.AdminDevicesService;
import com.chat99.server.common.ClientContext;
import com.chat99.server.common.PhoneUtils;
import com.chat99.server.im.ImAdminClient;
import com.chat99.server.notify.PlatformWalletNoticeService;
import com.chat99.server.notify.SystemNotifyService;
import com.chat99.server.sticker.StickerUserInitializer;
import com.chat99.server.wallet.WalletAccountService;
import com.chat99.server.security.JwtService;
import com.chat99.server.security.UserSessionService;
import com.chat99.server.sms.SmsCodeStore;
import com.chat99.server.sms.SmsScene;
import com.chat99.server.sms.SmsVerificationService;
import com.chat99.server.user.LoginLogService;
import com.chat99.server.user.NicknameService;
import com.chat99.server.user.PlatformIdGenerator;
import com.chat99.server.user.PresenceService;
import com.chat99.server.user.User;
import com.chat99.server.user.DeviceProperties;
import com.chat99.server.user.UserDeviceService;
import com.chat99.server.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final UserSessionService sessionService;
    private final SmsCodeStore codeStore;
    private final SmsVerificationService smsVerification;
    private final PhoneUtils phoneUtils;
    private final PlatformIdGenerator platformIdGenerator;
    private final ImAdminClient imAdmin;
    private final UserDeviceService deviceService;
    private final LoginLogService loginLogService;
    private final ClientContext clientContext;
    private final AuthProperties authProps;
    private final NicknameService nicknameService;
    private final SystemNotifyService systemNotifyService;
    private final PlatformWalletNoticeService platformWalletNoticeService;
    private final StickerUserInitializer stickerUserInitializer;
    private final WalletAccountService walletAccountService;
    private final DeviceProperties deviceProperties;
    private final SliderCaptchaService sliderCaptchaService;
    private final PresenceService presenceService;
    private final AdminDevicesService adminDevicesService;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder,
                          JwtService jwtService, UserSessionService sessionService,
                          SmsCodeStore codeStore, SmsVerificationService smsVerification,
                          PhoneUtils phoneUtils,
                          PlatformIdGenerator platformIdGenerator, ImAdminClient imAdmin,
                          UserDeviceService deviceService, LoginLogService loginLogService,
                          ClientContext clientContext, AuthProperties authProps,
                          NicknameService nicknameService,
                          SystemNotifyService systemNotifyService,
                          PlatformWalletNoticeService platformWalletNoticeService,
                          StickerUserInitializer stickerUserInitializer,
                          WalletAccountService walletAccountService,
                          DeviceProperties deviceProperties,
                          SliderCaptchaService sliderCaptchaService,
                          PresenceService presenceService,
                          AdminDevicesService adminDevicesService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.sessionService = sessionService;
        this.codeStore = codeStore;
        this.smsVerification = smsVerification;
        this.phoneUtils = phoneUtils;
        this.platformIdGenerator = platformIdGenerator;
        this.imAdmin = imAdmin;
        this.deviceService = deviceService;
        this.loginLogService = loginLogService;
        this.clientContext = clientContext;
        this.authProps = authProps;
        this.nicknameService = nicknameService;
        this.systemNotifyService = systemNotifyService;
        this.platformWalletNoticeService = platformWalletNoticeService;
        this.stickerUserInitializer = stickerUserInitializer;
        this.walletAccountService = walletAccountService;
        this.deviceProperties = deviceProperties;
        this.sliderCaptchaService = sliderCaptchaService;
        this.presenceService = presenceService;
        this.adminDevicesService = adminDevicesService;
    }

    public record RegisterRequest(
        @NotBlank String phone,
        @NotBlank String smsCode,
        @NotBlank String nickname,
        @NotBlank @Size(min = 6, max = 64) String password,
        String deviceId,
        String deviceModel) {}

    public record SmsLoginRequest(@NotBlank String phone, @NotBlank String smsCode,
                                  @NotBlank String deviceId, String deviceModel) {}

    public record PasswordLoginRequest(@NotBlank String account, @NotBlank String password,
                                       @NotBlank String deviceId, String phoneCountry,
                                       String deviceModel) {}

    public record PasswordVerifyRequest(@NotBlank String challengeId, @NotBlank String smsCode,
                                        @NotBlank String deviceId, String deviceModel) {}

    public record ResetPasswordRequest(
        @NotBlank String phone,
        @NotBlank String smsCode,
        @NotBlank @Size(min = 8, max = 64) String password) {}

    /** 未绑定手机账号：凭旧密码修改登录密码（无需短信）。 */
    public record ChangePasswordRequest(
        @NotBlank String oldPassword,
        @NotBlank @Size(min = 8, max = 64) String newPassword) {}

    public record NicknameRequest(@NotBlank String nickname) {}

    public record SliderCaptchaInitResponse(
        String token,
        int targetX,
        int targetY,
        int gapX,
        int gapY,
        int gapWidth,
        int gapHeight,
        List<Map<String, Integer>> fakeGaps,
        String background
    ) {}

    public record SliderCaptchaVerifyRequest(
        @NotBlank String token,
        @Min(0) int x,
        Integer y
    ) {}

    public record SliderCaptchaVerifyResponse(boolean success, String message) {}

    public record WalletRegisterInfo(String depositAddress, String usdtContract, String minDepositUsdt) {}

    public record TokenResponse(String token, long expiresIn, String userId, String nextStep,
                                WalletRegisterInfo wallet) {}

    public record ChallengeResponse(String nextStep, String challengeId, String phoneMasked, String phone) {}

    public record MeResponse(String userId, String phone, String phoneMasked, String nickname,
                             String avatarUrl, Integer avatarVersion, Instant lastNicknameChangedAt,
                             boolean bypassDeviceCheck) {}

    public record NicknameCheckResponse(boolean available, String reason) {}

    public record NicknameUpdateResponse(String nickname, Instant nextChangeableAt) {}

    @GetMapping("/auth/slider/init")
    public SliderCaptchaInitResponse initSliderCaptchaJson() {
        SliderCaptchaService.SliderCaptchaInitResult result = sliderCaptchaService.initCaptcha();

        return new SliderCaptchaInitResponse(
            result.token(),
            result.targetX(),
            result.targetY(),
            result.gapX(),
            result.gapY(),
            result.gapWidth(),
            result.gapHeight(),
            result.fakeGaps(),
            result.background()
        );
    }

    @PostMapping("/auth/slider/verify")
    public SliderCaptchaVerifyResponse verifySliderCaptcha(@Valid @RequestBody SliderCaptchaVerifyRequest req) {
        boolean ok = sliderCaptchaService.verifyCaptcha(
            req.token(),
            req.x(),
            req.y() != null ? req.y() : 0
        );
        if (!ok) {
            return new SliderCaptchaVerifyResponse(false, "滑块验证失败");
        }
        return new SliderCaptchaVerifyResponse(true, "验证成功");
    }

    @PostMapping("/auth/register")
    @Transactional
    public TokenResponse register(@Valid @RequestBody RegisterRequest req, HttpServletRequest http) {
        PhoneUtils.Parsed phone = phoneUtils.parseE164(req.phone());

        String nickname = nicknameService.normalize(req.nickname());
        nicknameService.validateFormat(nickname);

        if (!smsVerification.verifyAndConsume(SmsScene.REGISTER, phone.e164(), phone.countryCode(), req.smsCode())) {
            throw new ResponseStatusException(HttpStatus.GONE, "SMS_CODE_INVALID");
        }
        if (userRepository.existsByPhone(phone.e164())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PHONE_EXISTS");
        }
        if (userRepository.existsByNickname(nickname)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "NICKNAME_EXISTS");
        }

        String platformId = platformIdGenerator.allocate();

        User u = new User();
        u.setUserId(platformId);
        u.setPhone(phone.e164());
        u.setPhoneCountry(phone.countryCode());
        u.setPasswordHash(passwordEncoder.encode(req.password()));
        u.setNickname(nickname);
        u.setAvatarUrl(authProps.avatarUrl());
        userRepository.save(u);

        imAdmin.accountImport(platformId, nickname, authProps.avatarUrl());

        stickerUserInitializer.ensureInstalled(platformId);
        systemNotifyService.onUserRegistered(platformId);
        platformWalletNoticeService.onUserRegistered(platformId);

        WalletRegisterInfo walletInfo = null;
        try {
            WalletAccountService.RegisterWalletInfo created =
                walletAccountService.ensureWalletForRegister(u);
            if (created == null) {
                log.warn("wallet not created for new user {} because deposit mnemonic is not configured",
                    u.getUserId());
            } else {
                walletInfo = new WalletRegisterInfo(
                    created.depositAddress(), created.usdtContract(), created.minDepositUsdt());
            }
        } catch (Exception e) {
            log.warn("wallet creation failed for new user {}: {}", u.getUserId(), e.getMessage());
        }

        String deviceId = normalizeDeviceId(req.deviceId());
        if (!deviceId.isEmpty()) {
            deviceService.trust(u.getUserId(), deviceId, clientContext.platform(http),
                clientContext.deviceModel(http, req.deviceModel()));
            loginLogService.record(http, u.getUserId(), req.phone(), "REGISTER", deviceId, true, null);
        }
        return issueSessionToken(u.getUserId(), deviceId, clientContext.platform(http), walletInfo);
    }

    @PostMapping("/auth/login/sms")
    public TokenResponse loginSms(@Valid @RequestBody SmsLoginRequest req, HttpServletRequest http) {
        PhoneUtils.Parsed phone = phoneUtils.parseE164(req.phone());

        if (!smsVerification.verifyAndConsume(SmsScene.LOGIN, phone.e164(), phone.countryCode(), req.smsCode())) {
            loginLogService.record(http, null, req.phone(), "SMS", req.deviceId(), false, "SMS_CODE_INVALID");
            throw new ResponseStatusException(HttpStatus.GONE, "SMS_CODE_INVALID");
        }

        User u = userRepository.findByPhone(phone.e164()).orElse(null);
        if (u == null) {
            loginLogService.record(http, null, req.phone(), "SMS", req.deviceId(), false, "USER_NOT_FOUND");
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        }
        if (u.getStatus() != 1) {
            loginLogService.record(http, u.getUserId(), req.phone(), "SMS", req.deviceId(), false, "ACCOUNT_DISABLED");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED");
        }
        rejectIfDeviceBanned(http, u.getUserId(), req.phone(), "SMS", req.deviceId());

        deviceService.trust(u.getUserId(), req.deviceId(), clientContext.platform(http),
            clientContext.deviceModel(http, req.deviceModel()));
        loginLogService.record(http, u.getUserId(), req.phone(), "SMS", req.deviceId(), true, null);
        return issueSessionToken(u.getUserId(), req.deviceId(), clientContext.platform(http), null);
    }

    @PostMapping("/auth/login/password")
    public Object loginPassword(@Valid @RequestBody PasswordLoginRequest req, HttpServletRequest http) {
        User u = resolveAccount(req.account(), req.phoneCountry());
        if (u == null) {
            loginLogService.record(http, null, req.account(), "PASSWORD", req.deviceId(), false, "BAD_CREDENTIALS");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS");
        }
        if (u.getStatus() != 1) {
            loginLogService.record(http, u.getUserId(), req.account(), "PASSWORD", req.deviceId(), false, "ACCOUNT_DISABLED");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED");
        }
        if (!passwordEncoder.matches(req.password(), u.getPasswordHash())) {
            loginLogService.record(http, u.getUserId(), req.account(), "PASSWORD", req.deviceId(), false, "BAD_CREDENTIALS");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS");
        }
        rejectIfDeviceBanned(http, u.getUserId(), req.account(), "PASSWORD", req.deviceId());

        boolean bypass = u.isBypassDeviceCheck();
        boolean skipDeviceSms = u.isSkipDeviceSms();
        boolean trusted = deviceService.isTrusted(u.getUserId(), req.deviceId());
        boolean skipDeviceCheck = !deviceProperties.loginCheckEnabled();
        boolean noBoundPhone = !phoneUtils.hasBoundPhone(u.getPhone());
        if (skipDeviceCheck || bypass || skipDeviceSms || trusted || noBoundPhone) {
            String platform = clientContext.platform(http);
            String model = clientContext.deviceModel(http, req.deviceModel());
            if (skipDeviceCheck || bypass || skipDeviceSms || noBoundPhone) {
                deviceService.trust(u.getUserId(), req.deviceId(), platform, model);
                if (bypass) {
                    u.setBypassDeviceCheck(false);
                    userRepository.save(u);
                }
            } else {
                deviceService.touchLogin(u.getUserId(), req.deviceId(), platform, model);
            }
            loginLogService.record(http, u.getUserId(), req.account(), "PASSWORD", req.deviceId(), true, null);
            return issueSessionToken(u.getUserId(), req.deviceId(), clientContext.platform(http), null);
        }

        String boundPhone = u.getPhone();
        String challengeId = codeStore.createDeviceChallenge(boundPhone, u.getUserId(), req.deviceId());
        loginLogService.record(http, u.getUserId(), req.account(), "PASSWORD", req.deviceId(), false, "NEED_SMS");
        return new ChallengeResponse("NEED_SMS", challengeId, phoneUtils.mask(boundPhone), boundPhone);
    }

    @PostMapping("/auth/login/password/verify")
    public TokenResponse loginPasswordVerify(@Valid @RequestBody PasswordVerifyRequest req,
                                             HttpServletRequest http) {
        SmsCodeStore.DeviceChallenge ch = codeStore.readDeviceChallenge(req.challengeId());
        if (ch == null) {
            loginLogService.record(http, null, req.challengeId(), "PASSWORD_SMS_CHALLENGE",
                req.deviceId(), false, "CHALLENGE_EXPIRED");
            throw new ResponseStatusException(HttpStatus.GONE, "CHALLENGE_EXPIRED");
        }
        if (!smsVerification.matchesCode(ch.code(), req.smsCode()) || !ch.deviceId().equals(req.deviceId())) {
            loginLogService.record(http, ch.userId(), req.challengeId(), "PASSWORD_SMS_CHALLENGE",
                req.deviceId(), false, "SMS_CODE_INVALID");
            throw new ResponseStatusException(HttpStatus.GONE, "SMS_CODE_INVALID");
        }
        User u = userRepository.findByUserId(ch.userId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
        if (u.getStatus() != 1) {
            loginLogService.record(http, u.getUserId(), req.challengeId(), "PASSWORD_SMS_CHALLENGE",
                req.deviceId(), false, "ACCOUNT_DISABLED");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED");
        }
        rejectIfDeviceBanned(http, u.getUserId(), req.challengeId(), "PASSWORD_SMS_CHALLENGE", req.deviceId());

        deviceService.trust(u.getUserId(), req.deviceId(), clientContext.platform(http),
            clientContext.deviceModel(http, req.deviceModel()));
        codeStore.deleteDeviceChallenge(req.challengeId());
        loginLogService.record(http, u.getUserId(), u.getPhone(), "PASSWORD_SMS_CHALLENGE",
            req.deviceId(), true, null);
        return issueSessionToken(u.getUserId(), req.deviceId(), clientContext.platform(http), null);
    }

    @PostMapping("/auth/password/reset")
    @Transactional
    public TokenResponse resetPassword(@Valid @RequestBody ResetPasswordRequest req,
                                       HttpServletRequest http) {
        validateLoginPassword(req.password());
        PhoneUtils.Parsed phone = phoneUtils.parseE164(req.phone());

        if (!smsVerification.verifyAndConsume(SmsScene.RESET, phone.e164(), phone.countryCode(), req.smsCode())) {
            throw new ResponseStatusException(HttpStatus.GONE, "SMS_CODE_INVALID");
        }

        User u = userRepository.findByPhone(phone.e164())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
        if (u.getStatus() != 1) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED");
        }
        if (!phoneUtils.hasBoundPhone(u.getPhone())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PHONE_NOT_BOUND");
        }

        u.setPasswordHash(passwordEncoder.encode(req.password()));
        userRepository.save(u);

        deviceService.clearAllTrusted(u.getUserId());
        sessionService.revokeAll(u.getUserId());
        loginLogService.record(http, u.getUserId(), u.getPhone(), "PASSWORD_RESET", null, true, null);
        return issueSessionToken(u.getUserId(), "", null, null);
    }

    /**
     * 未绑定手机账号修改登录密码：旧密码 + 新密码，无需短信。
     * 已绑定手机的用户请走 {@link #resetPassword}（短信 RESET）。
     */
    @PostMapping("/auth/password/change")
    @Transactional
    public TokenResponse changePassword(Authentication auth,
                                        @Valid @RequestBody ChangePasswordRequest req,
                                        HttpServletRequest http) {
        String userId = (String) auth.getPrincipal();
        User u = userRepository.findByUserId(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
        if (u.getStatus() != 1) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED");
        }
        if (phoneUtils.hasBoundPhone(u.getPhone())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PHONE_ALREADY_BOUND");
        }
        validateLoginPassword(req.newPassword());
        if (!passwordEncoder.matches(req.oldPassword(), u.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "BAD_OLD_PASSWORD");
        }
        if (req.oldPassword().equals(req.newPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SAME_PASSWORD");
        }

        u.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        userRepository.save(u);
        deviceService.clearAllTrusted(u.getUserId());
        String currentDeviceId = currentDeviceId(http).orElse("");
        sessionService.revokeAllExcept(userId, currentDeviceId);
        loginLogService.record(http, u.getUserId(), u.getUserId(), "PASSWORD_CHANGE", currentDeviceId, true, null);
        return issueSessionToken(u.getUserId(), currentDeviceId, clientContext.platform(http), null);
    }

    @GetMapping("/me")
    public MeResponse me(Authentication auth) {
        String userId = (String) auth.getPrincipal();
        User u = userRepository.findByUserId(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
        String phone = phoneUtils.hasBoundPhone(u.getPhone()) ? u.getPhone() : "";
        String phoneMasked = phone.isEmpty() ? "" : phoneUtils.mask(phone);
        return new MeResponse(u.getUserId(), phone, phoneMasked,
            u.getNickname(), u.getAvatarUrl(), u.getAvatarVersion(), u.getLastNicknameChangedAt(), u.isBypassDeviceCheck());
    }

    @GetMapping("/me/nickname/check")
    public NicknameCheckResponse checkNickname(Authentication auth,
                                               @RequestParam @NotBlank String nickname) {
        NicknameService.NicknameCheckResult r = nicknameService.checkChangeable(
            (String) auth.getPrincipal(), nickname);
        return new NicknameCheckResponse(r.available(), r.reason());
    }

    @GetMapping("/nicknames/available")
    public NicknameCheckResponse checkNicknameAvailable(@RequestParam @NotBlank String nickname) {
        NicknameService.NicknameCheckResult r = nicknameService.checkAvailable(nickname);
        return new NicknameCheckResponse(r.available(), r.reason());
    }

    @PatchMapping("/me/nickname")
    public NicknameUpdateResponse updateNickname(Authentication auth,
                                                 @Valid @RequestBody NicknameRequest req) {
        NicknameService.NicknameUpdateResult r = nicknameService.update(
            (String) auth.getPrincipal(), req.nickname());
        return new NicknameUpdateResponse(r.nickname(), r.nextChangeableAt());
    }

    /**
     * 主动登出：撤销当前设备会话并禁用该设备离线 Push token。
     * 客户端仍建议在 IM SDK logout 前调用；未带 deviceId 的 token 无法定位设备。
     */
    @PostMapping("/auth/logout")
    public Map<String, Object> logout(Authentication auth, HttpServletRequest http) {
        String userId = (String) auth.getPrincipal();
        String deviceId = currentDeviceId(http).orElse("");
        if (deviceId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CURRENT_DEVICE_UNKNOWN");
        }
        sessionService.revokeDevice(userId, deviceId);
        log.info("user logged out userId={} deviceId={}", userId, deviceId);
        return Map.of("ok", true);
    }

    private TokenResponse issueSessionToken(String userId, String deviceId, String platform,
                                            WalletRegisterInfo wallet) {
        UserSessionService.SessionIssueResult session = sessionService.createSession(userId, deviceId, platform);
        presenceService.loginActive(userId);
        presenceService.deviceHeartbeat(userId, deviceId);
        return new TokenResponse(session.token(), session.expiresIn(), userId, "OK", wallet);
    }

    private java.util.Optional<String> currentDeviceId(HttpServletRequest http) {
        String header = http.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return java.util.Optional.empty();
        }
        return jwtService.parseDeviceId(header.substring(7));
    }

    private static String normalizeDeviceId(String deviceId) {
        return deviceId == null ? "" : deviceId.trim();
    }

    private void rejectIfDeviceBanned(HttpServletRequest http, String userId, String account,
                                      String loginType, String deviceId) {
        if (!adminDevicesService.isDeviceBanned(deviceId)) {
            return;
        }
        loginLogService.record(http, userId, account, loginType, deviceId, false, "DEVICE_BANNED");
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "DEVICE_BANNED");
    }

    private User resolveAccount(String account, String phoneCountry) {
        if (account == null || account.isBlank()) {
            return null;
        }
        try {
            if (account.startsWith("+")) {
                PhoneUtils.Parsed p = phoneUtils.parseWithRegion(account, null);
                return userRepository.findByPhone(p.e164()).orElse(null);
            }
            if (account.matches("^[0-9]+$")) {
                String region = (phoneCountry != null && !phoneCountry.isBlank()) ? phoneCountry : "CN";
                PhoneUtils.Parsed p = phoneUtils.parseWithRegion(account, region);
                return userRepository.findByPhone(p.e164()).orElse(null);
            }
            return userRepository.findByUserId(account).orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 与客户端 ChangePasswordPage 一致：至少 8 位，且含字母与数字。 */
    private static void validateLoginPassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 64) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PASSWORD");
        }
        if (!password.matches(".*[A-Za-z].*") || !password.matches(".*\\d.*")) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PASSWORD");
        }
    }

}
