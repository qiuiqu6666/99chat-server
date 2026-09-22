package com.chat99.server.wallet;

import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletChainBalanceService {

    private final UserWalletRepository walletRepository;
    private final TronGridClient tronGrid;

    public WalletChainBalanceService(UserWalletRepository walletRepository, TronGridClient tronGrid) {
        this.walletRepository = walletRepository;
        this.tronGrid = tronGrid;
    }

    @Transactional
    public Optional<TronGridClient.AccountBalances> refreshAndSave(UserWallet wallet) {
        Optional<TronGridClient.AccountBalances> chainOpt = tronGrid.fetchAccountBalances(wallet.getTronAddress());
        chainOpt.ifPresent(chain -> applyChainBalance(wallet, chain));
        if (chainOpt.isPresent()) {
            walletRepository.save(wallet);
        }
        return chainOpt;
    }

    public void applyChainBalance(UserWallet wallet, TronGridClient.AccountBalances chain) {
        wallet.setChainUsdtMicro(chain.usdtMicro());
        wallet.setChainTrxSun(chain.trxSun());
        wallet.setChainBalanceAt(Instant.now());
    }

    public boolean hasCachedChainBalance(UserWallet wallet) {
        return wallet.getChainBalanceAt() != null;
    }

    public TronGridClient.AccountBalances cachedBalances(UserWallet wallet) {
        return new TronGridClient.AccountBalances(wallet.getChainTrxSun(), wallet.getChainUsdtMicro());
    }
}
