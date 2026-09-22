package com.chat99.server.integration;

import com.chat99.server.wallet.WalletCurrency;
import com.chat99.server.wallet.WalletExceptions;
import com.chat99.server.wallet.WalletLiveTipService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class IntegrationWalletLiveTipService {

    private final WalletLiveTipService liveTipService;

    public IntegrationWalletLiveTipService(WalletLiveTipService liveTipService) {
        this.liveTipService = liveTipService;
    }

    public Map<String, Object> tip(String fromUserId, IntegrationWalletLiveTipController.LiveTipRequest req) {
        if (fromUserId == null || fromUserId.isBlank() || req == null) {
            throw WalletExceptions.of(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (blank(req.toUserId()) || blank(req.currency()) || blank(req.clientOrderId()) || req.amount() == null) {
            throw WalletExceptions.of(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        WalletCurrency currency;
        try {
            currency = WalletCurrency.fromApiCode(req.currency());
        } catch (IllegalArgumentException e) {
            throw WalletExceptions.of(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        return liveTipService.tip(
            fromUserId.trim(),
            req.toUserId().trim(),
            currency,
            req.amount(),
            req.payPin(),
            req.clientOrderId().trim(),
            req.liveTipOrderId());
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }
}
