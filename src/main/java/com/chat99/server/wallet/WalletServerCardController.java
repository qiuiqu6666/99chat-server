package com.chat99.server.wallet;

import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/wallet/card-orders")
@ConditionalOnProperty(name = "wallet.proxy-enabled", havingValue = "false", matchIfMissing = true)
public class WalletServerCardController {
    private final WalletServerCardOrders orders;
    public WalletServerCardController(WalletServerCardOrders orders) { this.orders = orders; }

    @PostMapping("/transfer")
    public Map<String, Object> transfer(Authentication auth, @Valid @RequestBody WalletController.TransferRequest req) {
        return orders.transfer(userId(auth), req);
    }
    @PostMapping("/red-packet")
    public Map<String, Object> redPacket(Authentication auth, @Valid @RequestBody WalletController.RedPacketSendRequest req) {
        return orders.redPacket(userId(auth), req);
    }
    @GetMapping("/{clientId}")
    public Map<String, Object> status(Authentication auth, @PathVariable String clientId) {
        return orders.status(userId(auth), clientId);
    }
    private static String userId(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken || auth.getName() == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return auth.getName();
    }
}
