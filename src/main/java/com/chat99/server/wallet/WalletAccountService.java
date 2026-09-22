/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.wallet;

import com.chat99.server.user.User;
import com.chat99.server.user.UserRepository;
import com.chat99.server.wallet.TronHdWalletService;
import com.chat99.server.wallet.UserWallet;
import com.chat99.server.wallet.UserWalletRepository;
import com.chat99.server.wallet.WalletAccountService;
import com.chat99.server.wallet.WalletConfigService;
import com.chat99.server.wallet.WalletExceptions;
import com.chat99.server.wallet.WalletProperties;
import com.chat99.server.wallet.WalletSecretCipher;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletAccountService {
    private static final Logger log = LoggerFactory.getLogger(WalletAccountService.class);
    private final UserRepository userRepository;
    private final UserWalletRepository walletRepository;
    private final TronHdWalletService hdWallet;
    private final WalletProperties props;
    private final WalletConfigService configService;
    private final WalletSecretCipher secretCipher;

    public WalletAccountService(UserRepository userRepository, UserWalletRepository walletRepository, TronHdWalletService hdWallet, WalletProperties props, WalletConfigService configService, WalletSecretCipher secretCipher) {
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.hdWallet = hdWallet;
        this.props = props;
        this.configService = configService;
        this.secretCipher = secretCipher;
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public RegisterWalletInfo ensureWalletForRegister(User user) {
        UserWallet wallet = this.ensureWallet(user);
        if (wallet == null) {
            return null;
        }
        WalletInfo info = this.walletInfo(wallet);
        return new RegisterWalletInfo(info.depositAddress(), info.usdtContract(), info.minDepositUsdt());
    }

    @Transactional
    public UserWallet ensureWallet(User user) {
        return this.walletRepository.findById(user.getUserId()).orElseGet(() -> this.createWallet(user));
    }

    @Transactional
    public UserWallet ensureWalletByUserId(String userId) {
        return this.walletRepository.findById(userId).orElseGet(() -> {
            User u = this.userRepository.findByUserId(userId).orElseThrow(() -> WalletExceptions.of((HttpStatus)HttpStatus.NOT_FOUND, (String)"USER_NOT_FOUND"));
            return this.createWallet(u);
        });
    }

    public WalletInfo walletInfo(UserWallet w) {
        String usdtContract = this.configService.getUsdtContract();
        return new WalletInfo(w.getTronAddress(), usdtContract, "1");
    }

    private UserWallet createWallet(User user) {
        if (user.getId() == null) {
            log.error("wallet create failed for user {} because database id is null", (Object)user.getUserId());
            throw new IllegalStateException("user id required for HD index");
        }
        if (!this.configService.isDepositMnemonicConfigured()) {
            log.warn("wallet creation skipped for user {} because deposit mnemonic is not configured in database", (Object)user.getUserId());
            return null;
        }
        long index = user.getId();
        String address = this.hdWallet.deriveAddress(index);
        String privateKeyHex = this.hdWallet.derivePrivateKeyHex(index);
        UserWallet w = new UserWallet();
        w.setUserId(user.getUserId());
        w.setTronAddress(address);
        w.setTronPrivateKey(this.secretCipher.seal(WalletAccountService.normalizePrivateKeyHex(privateKeyHex)));
        w.setDerivationIndex(index);
        w.setBalanceUsdtMicro(0L);
        w.setBalancePlatformFen(0L);
        w.setBalanceTrxSun(0L);
        UserWallet saved = this.walletRepository.save(w);
        log.info("wallet created for user {} with address {}", (Object)user.getUserId(), (Object)address);
        return saved;
    }

    @Transactional
    public int backfillMissingPrivateKeys() {
        if (!this.configService.isDepositMnemonicConfigured()) {
            return 0;
        }
        List<UserWallet> missing = this.walletRepository.findMissingPrivateKey();
        int n = 0;
        for (UserWallet w : missing) {
            try {
                String privateKeyHex = this.hdWallet.derivePrivateKeyHex(w.getDerivationIndex());
                String address = this.hdWallet.deriveAddress(w.getDerivationIndex());
                if (w.getTronAddress() != null && !w.getTronAddress().equalsIgnoreCase(address)) {
                    log.error("wallet private key backfill skipped: address mismatch userId={} stored={} derived={}", new String[]{w.getUserId(), w.getTronAddress(), address});
                    continue;
                }
                w.setTronPrivateKey(this.secretCipher.seal(WalletAccountService.normalizePrivateKeyHex(privateKeyHex)));
                this.walletRepository.save(w);
                ++n;
            }
            catch (Exception e) {
                log.warn("wallet private key backfill failed userId={} err={}", (Object)w.getUserId(), (Object)e.getMessage());
            }
        }
        return n;
    }

    @Transactional
    public String requirePrivateKeyHex(UserWallet wallet) {
        if (wallet == null) {
            throw WalletExceptions.of((HttpStatus)HttpStatus.NOT_FOUND, (String)"WALLET_NOT_FOUND");
        }
        if (wallet.getTronPrivateKey() != null && !wallet.getTronPrivateKey().isBlank()) {
            String privateKeyHex = WalletAccountService.normalizePrivateKeyHex(
                this.secretCipher.open(wallet.getTronPrivateKey().trim()));
            verifyKeyMatchesAddress(wallet, privateKeyHex);
            return privateKeyHex;
        }
        if (!this.configService.isDepositMnemonicConfigured()) {
            throw WalletExceptions.of((HttpStatus)HttpStatus.PRECONDITION_FAILED, (String)"DEPOSIT_MNEMONIC_NOT_CONFIGURED");
        }
        String privateKeyHex = WalletAccountService.normalizePrivateKeyHex(
            this.hdWallet.derivePrivateKeyHex(wallet.getDerivationIndex()));
        verifyKeyMatchesAddress(wallet, privateKeyHex);
        wallet.setTronPrivateKey(this.secretCipher.seal(privateKeyHex));
        this.walletRepository.save(wallet);
        return privateKeyHex;
    }

    private void verifyKeyMatchesAddress(UserWallet wallet, String privateKeyHex) {
        String derived = TronTransactionSigner.privateKeyToBase58Address(privateKeyHex);
        if (wallet.getTronAddress() != null && !wallet.getTronAddress().equalsIgnoreCase(derived)) {
            log.error("wallet private key/address mismatch userId={} storedAddr={} keyAddr={}",
                wallet.getUserId(), wallet.getTronAddress(), derived);
            throw WalletExceptions.of(HttpStatus.CONFLICT, "WALLET_KEY_ADDRESS_MISMATCH",
                "充值地址与私钥不匹配，无法归集，请检查助记词/密钥配置");
        }
    }

    static String normalizePrivateKeyHex(String privateKeyHex) {
        if (privateKeyHex == null || privateKeyHex.isBlank()) {
            throw new IllegalStateException("empty private key");
        }
        Object hex = privateKeyHex.trim();
        if (((String)hex).startsWith("0x") || ((String)hex).startsWith("0X")) {
            hex = ((String)hex).substring(2);
        }
        if (!((String)hex).matches("(?i)[0-9a-f]+")) {
            throw new IllegalStateException("invalid private key hex");
        }
        if (((String)hex).length() > 64) {
            throw new IllegalStateException("private key hex too long");
        }
        if (((String)hex).length() < 64) {
            hex = "0".repeat(64 - ((String)hex).length()) + (String)hex;
        }
        return ((String)hex).toLowerCase();
    }





    public record RegisterWalletInfo(String depositAddress, String usdtContract, String minDepositUsdt) {}

    public record WalletInfo(String depositAddress, String usdtContract, String minDepositUsdt) {}
}
