package com.chat99.server.wallet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 充值扫描仅覆盖曾上线用户；首次活跃时把地址写入 block-scan 地址簿。 */
@Service
public class DepositScanEligibilityService {

    private static final Logger log = LoggerFactory.getLogger(DepositScanEligibilityService.class);

    private final UserWalletRepository walletRepository;
    private final DepositAddressBook addressBook;

    public DepositScanEligibilityService(UserWalletRepository walletRepository,
                                         DepositAddressBook addressBook) {
        this.walletRepository = walletRepository;
        this.addressBook = addressBook;
    }

    /** 用户首次/后续活跃后确保地址进入扫描范围（block-scan 地址簿）。 */
    public void ensureRegistered(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        walletRepository.findById(userId.trim()).ifPresent(wallet -> {
            if (addressBook.resolveUserId(wallet.getTronAddress()).isPresent()) {
                return;
            }
            addressBook.register(wallet.getTronAddress(), wallet.getUserId());
            log.debug("deposit scan registered address for userId={}", wallet.getUserId());
        });
    }
}
