package com.chat99.server.wallet;

import com.chat99.server.user.User;
import com.chat99.server.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class WalletAddressBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WalletAddressBackfillRunner.class);

    private final UserRepository userRepository;
    private final UserWalletRepository walletRepository;
    private final WalletAccountService walletAccountService;
    private final WalletConfigService configService;
    private final DepositAddressBook addressBook;

    public WalletAddressBackfillRunner(UserRepository userRepository, UserWalletRepository walletRepository,
                                       WalletAccountService walletAccountService, WalletConfigService configService,
                                       DepositAddressBook addressBook) {
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.walletAccountService = walletAccountService;
        this.configService = configService;
        this.addressBook = addressBook;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!configService.isDepositMnemonicConfigured()) {
            log.info("wallet backfill skipped: deposit mnemonic not configured in database");
            return;
        }
        int n = 0;
        for (User u : userRepository.findAll()) {
            if (walletRepository.findById(u.getUserId()).isEmpty()) {
                try {
                    walletAccountService.ensureWallet(u);
                    n++;
                } catch (Exception e) {
                    log.warn("wallet backfill failed userId={} err={}", u.getUserId(), e.getMessage());
                }
            }
        }
        if (n > 0) {
            log.info("wallet address backfill created {} wallets", n);
        }
        int keys = walletAccountService.backfillMissingPrivateKeys();
        if (keys > 0) {
            log.info("wallet private key backfill saved {} keys", keys);
        }
        addressBook.rebuildFromDatabase(walletRepository);
    }
}
