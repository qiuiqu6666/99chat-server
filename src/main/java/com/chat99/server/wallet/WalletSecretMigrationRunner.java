/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.wallet;

import com.chat99.server.common.AppSettingRepository;
import com.chat99.server.wallet.TronTransactionSigner;
import com.chat99.server.wallet.UserWallet;
import com.chat99.server.wallet.UserWalletRepository;
import com.chat99.server.wallet.WalletSecretCipher;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(value=40)
public class WalletSecretMigrationRunner
implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(WalletSecretMigrationRunner.class);
    private static final String KEY_DEPOSIT_MNEMONIC = "wallet.deposit_mnemonic";
    private static final String KEY_HOT_WALLET_PRIVATE_KEY = "wallet.hot_wallet_private_key";
    private static final int SAMPLE_LIMIT = 5;
    private final WalletSecretCipher cipher;
    private final UserWalletRepository walletRepository;
    private final AppSettingRepository appSettingRepository;

    public WalletSecretMigrationRunner(WalletSecretCipher cipher, UserWalletRepository walletRepository, AppSettingRepository appSettingRepository) {
        this.cipher = cipher;
        this.walletRepository = walletRepository;
        this.appSettingRepository = appSettingRepository;
    }

    @Transactional
    public void run(ApplicationArguments args) {
        if (!this.cipher.enabled()) {
            log.warn("wallet secret encryption disabled: WALLET_DATA_ENCRYPTION_KEY not set");
            return;
        }
        int settings = this.migrateSettings();
        int wallets = this.migrateWallets();
        int verified = this.sampleVerify();
        log.info("wallet secret migration done settings={} wallets={} samplesVerified={}", settings, wallets, verified);
    }

    private int migrateSettings() {
        int n = 0;
        n += this.sealSetting(KEY_DEPOSIT_MNEMONIC);
        return n += this.sealSetting(KEY_HOT_WALLET_PRIVATE_KEY);
    }

    private int sealSetting(String key) {
        return this.appSettingRepository.findById(key).map(setting -> {
            String value = setting.getValue();
            if (value == null || value.isBlank() || WalletSecretCipher.isSealed(value)) {
                return 0;
            }
            setting.setValue(this.cipher.seal(value));
            this.appSettingRepository.save(setting);
            return 1;
        }).orElse(0);
    }

    private int migrateWallets() {
        List<UserWallet> all = this.walletRepository.findAll();
        int n = 0;
        for (UserWallet wallet : all) {
            String stored = wallet.getTronPrivateKey();
            if (stored == null || stored.isBlank() || WalletSecretCipher.isSealed(stored)) continue;
            wallet.setTronPrivateKey(this.cipher.seal(stored));
            this.walletRepository.save(wallet);
            ++n;
        }
        return n;
    }

    private int sampleVerify() {
        List<UserWallet> sample = this.walletRepository.findAll().stream().filter(w -> w.getTronPrivateKey() != null && !w.getTronPrivateKey().isBlank()).limit(5L).toList();
        int ok = 0;
        for (UserWallet wallet : sample) {
            try {
                String privateKey = this.cipher.open(wallet.getTronPrivateKey());
                String derived = TronTransactionSigner.privateKeyToBase58Address((String)privateKey);
                if (wallet.getTronAddress() != null && wallet.getTronAddress().equalsIgnoreCase(derived)) {
                    ++ok;
                    continue;
                }
                log.error("wallet secret sample mismatch userId={} stored={} derived={}", new String[]{wallet.getUserId(), wallet.getTronAddress(), derived});
            }
            catch (Exception e) {
                log.error("wallet secret sample verify failed userId={} err={}", (Object)wallet.getUserId(), (Object)e.getMessage());
            }
        }
        return ok;
    }
}
