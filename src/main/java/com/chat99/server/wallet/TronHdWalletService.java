package com.chat99.server.wallet;

import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import java.util.Arrays;
import org.bitcoinj.wallet.DeterministicSeed;
import org.springframework.stereotype.Service;

@Service
public class TronHdWalletService {

    private static final int COIN_TYPE_TRON = 195;

    private final WalletConfigService configService;

    public TronHdWalletService(WalletConfigService configService) {
        this.configService = configService;
    }

    public String deriveAddress(long index) {
        if (!configService.isDepositMnemonicConfigured()) {
            throw new IllegalStateException("Deposit mnemonic is not configured in database");
        }
        DeterministicKey child = deriveKey(index);
        byte[] uncompressed = child.getPubKeyPoint().getEncoded(false);
        return TronAddressUtils.fromPublicKeyUncompressed(uncompressed);
    }

    public String derivePrivateKeyHex(long index) {
        if (!configService.isDepositMnemonicConfigured()) {
            throw new IllegalStateException("Deposit mnemonic is not configured in database");
        }
        return deriveKey(index).getPrivateKeyAsHex();
    }

    private DeterministicKey deriveKey(long index) {
        try {
            String mnemonic = configService.getDepositMnemonic();
            DeterministicSeed seedWrapper = new DeterministicSeed(
                Arrays.asList(mnemonic.trim().split("\\s+")),
                null, "", 0L);
            DeterministicKey master = HDKeyDerivation.createMasterPrivateKey(seedWrapper.getSeedBytes());
            DeterministicKey purpose = HDKeyDerivation.deriveChildKey(master, new ChildNumber(44, true));
            DeterministicKey coin = HDKeyDerivation.deriveChildKey(purpose, new ChildNumber(COIN_TYPE_TRON, true));
            DeterministicKey account = HDKeyDerivation.deriveChildKey(coin, new ChildNumber(0, true));
            DeterministicKey external = HDKeyDerivation.deriveChildKey(account, ChildNumber.ZERO);
            return HDKeyDerivation.deriveChildKey(external, new ChildNumber((int) index, false));
        } catch (Exception e) {
            throw new IllegalStateException("HD derive failed: " + e.getMessage(), e);
        }
    }
}
