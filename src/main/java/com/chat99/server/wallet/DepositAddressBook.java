package com.chat99.server.wallet;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class DepositAddressBook {

    private static final Logger log = LoggerFactory.getLogger(DepositAddressBook.class);
    static final String HASH_KEY = "deposit:addr2user";

    private final StringRedisTemplate redis;

    public DepositAddressBook(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void register(String tronAddress, String userId) {
        if (tronAddress == null || tronAddress.isBlank() || userId == null || userId.isBlank()) {
            return;
        }
        redis.opsForHash().put(HASH_KEY, tronAddress, userId);
    }

    public void unregister(String tronAddress) {
        if (tronAddress != null && !tronAddress.isBlank()) {
            redis.opsForHash().delete(HASH_KEY, tronAddress);
        }
    }

    public Optional<String> resolveUserId(String tronAddress) {
        if (tronAddress == null || tronAddress.isBlank()) {
            return Optional.empty();
        }
        Object v = redis.opsForHash().get(HASH_KEY, tronAddress);
        return v == null || v.toString().isBlank() ? Optional.empty() : Optional.of(v.toString());
    }

    public long size() {
        Long n = redis.opsForHash().size(HASH_KEY);
        return n == null ? 0L : n;
    }

    public void rebuildFromDatabase(UserWalletRepository walletRepository) {
        List<UserWallet> eligible = walletRepository.findAllEligibleForDepositScan();
        redis.delete(HASH_KEY);
        for (UserWallet w : eligible) {
            register(w.getTronAddress(), w.getUserId());
        }
        log.info("deposit address book rebuilt entries={} (online users only)", eligible.size());
    }
}
