package com.chat99.server.wallet;

import com.chat99.server.common.PhoneUtils;
import com.chat99.server.notify.PlatformWalletNoticeService;
import com.chat99.server.sms.SmsScene;
import com.chat99.server.sms.SmsVerificationService;
import com.chat99.server.user.User;
import com.chat99.server.user.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PayPinService {

    private final UserWalletRepository walletRepository;
    private final UserRepository userRepository;
    private final SmsVerificationService smsVerification;
    private final PasswordEncoder passwordEncoder;
    private final WalletConfigService configService;
    private final PlatformWalletNoticeService platformWalletNotice;
    private final PhoneUtils phoneUtils;

    public PayPinService(UserWalletRepository walletRepository, UserRepository userRepository,
                         SmsVerificationService smsVerification, PasswordEncoder passwordEncoder,
                         WalletConfigService configService, PlatformWalletNoticeService platformWalletNotice,
                         PhoneUtils phoneUtils) {
        this.walletRepository = walletRepository;
        this.userRepository = userRepository;
        this.smsVerification = smsVerification;
        this.passwordEncoder = passwordEncoder;
        this.configService = configService;
        this.platformWalletNotice = platformWalletNotice;
        this.phoneUtils = phoneUtils;
    }

    public boolean isSet(String userId) {
        UserWallet w = walletRepository.findById(userId).orElse(null);
        return w != null && w.getPayPinHash() != null && !w.getPayPinHash().isBlank();
    }

    @Transactional
    public void setPin(String userId, String pin) {
        requireActiveUser(userId);
        validatePinFormat(pin);
        UserWallet w = walletRepository.findById(userId)
            .orElseThrow(() -> WalletExceptions.of(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND"));
        if (w.getPayPinHash() != null) {
            throw WalletExceptions.of(HttpStatus.CONFLICT, "PAY_PIN_ALREADY_SET");
        }
        w.setPayPinHash(passwordEncoder.encode(pin));
        w.setPayPinFailCount(0);
        w.setPayPinLockedUntil(null);
        walletRepository.save(w);
        platformWalletNotice.notifySetTradePassword(userId);
    }

    /**
     * 短信重置支付密码（无需旧密码）。验证码须为 scene=PAY_PIN_RESET 且手机号与账号绑定手机一致。
     */
    @Transactional
    public void resetWithSms(String userId, String smsCode, String newPin) {
        User user = requireBoundPhone(userId);
        validatePinFormat(newPin);
        if (!smsVerification.verifyAndConsume(SmsScene.PAY_PIN_RESET, user.getPhone(),
            user.getPhoneCountry(), smsCode)) {
            throw WalletExceptions.of(HttpStatus.GONE, "SMS_CODE_INVALID");
        }
        UserWallet w = walletRepository.findById(userId)
            .orElseThrow(() -> WalletExceptions.of(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND"));
        w.setPayPinHash(passwordEncoder.encode(newPin));
        w.setPayPinFailCount(0);
        w.setPayPinLockedUntil(null);
        walletRepository.save(w);
        platformWalletNotice.notifyChangeTradePassword(userId);
    }

    @Transactional
    public void changePin(String userId, String oldPin, String newPin) {
        requireActiveUser(userId);
        validatePinFormat(newPin);
        UserWallet w = requireWallet(userId);
        verifyInternal(w, oldPin);
        w.setPayPinHash(passwordEncoder.encode(newPin));
        w.setPayPinFailCount(0);
        w.setPayPinLockedUntil(null);
        walletRepository.save(w);
        platformWalletNotice.notifyChangeTradePassword(userId);
    }

    public void requireSetAndVerify(String userId, String pin) {
        UserWallet w = requireWallet(userId);
        if (w.getPayPinHash() == null || w.getPayPinHash().isBlank()) {
            throw WalletExceptions.of(HttpStatus.FORBIDDEN, "PAY_PIN_NOT_SET");
        }
        verifyInternal(w, pin);
    }

    private void verifyInternal(UserWallet w, String pin) {
        if (w.getPayPinLockedUntil() != null && Instant.now().isBefore(w.getPayPinLockedUntil())) {
            throw WalletExceptions.of(HttpStatus.FORBIDDEN, "PAY_PIN_LOCKED");
        }
        if (!passwordEncoder.matches(pin, w.getPayPinHash())) {
            w.setPayPinFailCount(w.getPayPinFailCount() + 1);
            if (w.getPayPinFailCount() >= configService.getPayPinMaxFailures()) {
                w.setPayPinLockedUntil(Instant.now().plus(configService.getPayPinLockMinutes(), ChronoUnit.MINUTES));
                w.setPayPinFailCount(0);
            }
            walletRepository.save(w);
            throw WalletExceptions.of(HttpStatus.FORBIDDEN, "PAY_PIN_INVALID");
        }
        w.setPayPinFailCount(0);
        w.setPayPinLockedUntil(null);
        walletRepository.save(w);
    }

    private UserWallet requireWallet(String userId) {
        return walletRepository.findById(userId)
            .orElseThrow(() -> WalletExceptions.of(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND"));
    }

    private User requireActiveUser(String userId) {
        User user = userRepository.findByUserId(userId)
            .orElseThrow(() -> WalletExceptions.of(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
        if (user.getStatus() != 1) {
            throw WalletExceptions.of(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED");
        }
        return user;
    }

    private User requireBoundPhone(String userId) {
        User user = requireActiveUser(userId);
        if (!phoneUtils.hasBoundPhone(user.getPhone())) {
            throw WalletExceptions.of(HttpStatus.BAD_REQUEST, "PHONE_NOT_BOUND");
        }
        return user;
    }

    private static void validatePinFormat(String pin) {
        if (pin == null || !pin.matches("\\d{6}")) {
            throw WalletExceptions.of(HttpStatus.BAD_REQUEST, "INVALID_PAY_PIN");
        }
    }
}
