/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.wallet;

import com.chat99.server.adminapi.AdminAccess;
import com.chat99.server.adminapi.AdminApiException;
import com.chat99.server.adminapi.AdminPrincipal;
import com.chat99.server.wallet.WalletConfigController;
import com.chat99.server.wallet.WalletConfigService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value={"/api/v1/wallet/config"})
public class WalletConfigController {
    private final WalletConfigService configService;

    public WalletConfigController(WalletConfigService configService) {
        this.configService = configService;
    }

    @GetMapping
    public ResponseEntity<ConfigResponse> getConfig(Authentication auth) {
        WalletConfigController.requireSystemConfig(auth);
        return ResponseEntity.ok(new ConfigResponse(WalletConfigController.configured(this.configService.getDepositMnemonic()), WalletConfigController.configured(this.configService.getHotWalletPrivateKey()), WalletConfigController.configured(this.configService.getTrongridApiKey()), this.configService.getTrongridBaseUrl(), this.configService.getUsdtContract(), this.configService.getDepositMode(), this.configService.getMinDepositUsdtMicro(), this.configService.getDepositConfirmations(), this.configService.getDepositHotTtlMinutes(), this.configService.getTrongridQpsLimit(), this.configService.getDepositScanConcurrency(), this.configService.getDepositBlockScanBatchSize(), this.configService.getPayPinMaxFailures(), this.configService.getPayPinLockMinutes(), this.configService.getRedPacketExpireHours(), this.configService.getExchangeRateCacheSeconds(), this.configService.getFrankfurterUrl(), this.configService.getDepositColdBatchSize(), this.configService.getDepositColdLookbackDays(), this.configService.getDepositScanMaxRoundMs()));
    }

    @PatchMapping
    public ResponseEntity<Void> updateConfig(Authentication auth, @RequestBody UpdateRequest req) {
        WalletConfigController.requireSystemConfig(auth);
        if (req == null || req.key() == null || req.value() == null) {
            return ResponseEntity.badRequest().build();
        }
        switch (req.key()) {
            case "depositMnemonic": {
                this.configService.setDepositMnemonic(req.value());
                break;
            }
            case "hotWalletPrivateKey": {
                this.configService.setHotWalletPrivateKey(req.value());
                break;
            }
            case "trongridApiKey": {
                this.configService.setTrongridApiKey(req.value());
                break;
            }
            case "trongridBaseUrl": {
                this.configService.setTrongridBaseUrl(req.value());
                break;
            }
            case "usdtContract": {
                this.configService.setUsdtContract(req.value());
                break;
            }
            case "depositMode": {
                this.configService.setDepositMode(req.value());
                break;
            }
            case "minDepositUsdtMicro": {
                this.configService.setMinDepositUsdtMicro(Long.parseLong(req.value()));
                break;
            }
            case "depositConfirmations": {
                this.configService.setDepositConfirmations(Integer.parseInt(req.value()));
                break;
            }
            case "depositHotTtlMinutes": {
                this.configService.setDepositHotTtlMinutes(Integer.parseInt(req.value()));
                break;
            }
            case "trongridQpsLimit": {
                this.configService.setTrongridQpsLimit(Integer.parseInt(req.value()));
                break;
            }
            case "depositScanConcurrency": {
                this.configService.setDepositScanConcurrency(Integer.parseInt(req.value()));
                break;
            }
            case "depositBlockScanBatchSize": {
                this.configService.setDepositBlockScanBatchSize(Integer.parseInt(req.value()));
                break;
            }
            case "payPinMaxFailures": {
                this.configService.setPayPinMaxFailures(Integer.parseInt(req.value()));
                break;
            }
            case "payPinLockMinutes": {
                this.configService.setPayPinLockMinutes(Integer.parseInt(req.value()));
                break;
            }
            case "redPacketExpireHours": {
                this.configService.setRedPacketExpireHours(Integer.parseInt(req.value()));
                break;
            }
            case "exchangeRateCacheSeconds": {
                this.configService.setExchangeRateCacheSeconds(Integer.parseInt(req.value()));
                break;
            }
            case "frankfurterUrl": {
                this.configService.setFrankfurterUrl(req.value());
                break;
            }
            case "depositColdBatchSize": {
                this.configService.setDepositColdBatchSize(Integer.parseInt(req.value()));
                break;
            }
            case "depositColdLookbackDays": {
                this.configService.setDepositColdLookbackDays(Integer.parseInt(req.value()));
                break;
            }
            case "depositScanMaxRoundMs": {
                this.configService.setDepositScanMaxRoundMs(Long.parseLong(req.value()));
                break;
            }
            default: {
                return ResponseEntity.notFound().build();
            }
        }
        return ResponseEntity.ok().build();
    }

    private static void requireSystemConfig(Authentication auth) {
        AdminPrincipal principal = AdminAccess.require((Authentication)auth);
        if (!principal.hasPermission("system.config") && !principal.hasPermission("admin.manage")) {
            throw new AdminApiException(HttpStatus.FORBIDDEN, "forbidden", "forbidden");
        }
    }

    private static String configured(String value) {
        return value == null || value.isBlank() ? "" : "configured";
    }





    public record ConfigResponse(String depositMnemonic, String hotWalletPrivateKey, String trongridApiKey, String trongridBaseUrl, String usdtContract, String depositMode, long minDepositUsdtMicro, int depositConfirmations, int depositHotTtlMinutes, int trongridQpsLimit, int depositScanConcurrency, int depositBlockScanBatchSize, int payPinMaxFailures, int payPinLockMinutes, int redPacketExpireHours, int exchangeRateCacheSeconds, String frankfurterUrl, int depositColdBatchSize, int depositColdLookbackDays, long depositScanMaxRoundMs) {}

    public record UpdateRequest(String key, String value) {}
}
