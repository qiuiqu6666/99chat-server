package com.chat99.server.wallet;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(100)
public class DepositAddressBookBootstrap implements ApplicationRunner {

    private final DepositAddressBook addressBook;
    private final UserWalletRepository walletRepository;

    public DepositAddressBookBootstrap(DepositAddressBook addressBook, UserWalletRepository walletRepository) {
        this.addressBook = addressBook;
        this.walletRepository = walletRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        addressBook.rebuildFromDatabase(walletRepository);
    }
}
