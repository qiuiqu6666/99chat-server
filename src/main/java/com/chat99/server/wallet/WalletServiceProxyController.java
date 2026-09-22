package com.chat99.server.wallet;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * 资金模块统一入口代理：仅当显式开启 {@code wallet.proxy-enabled=true} 时注册。
 * <p>
 * 默认关闭，不影响现网主服本地 {@link WalletController} 处理 /wallet/**。
 * 模拟切流见 {@code docs/microservice-split-safe-playbook.md}。
 */
@ConditionalOnProperty(name = "wallet.proxy-enabled", havingValue = "true")
@RestController
public class WalletServiceProxyController {

    private final WalletServiceHttpProxy walletServiceHttpProxy;

    public WalletServiceProxyController(WalletServiceHttpProxy walletServiceHttpProxy) {
        this.walletServiceHttpProxy = walletServiceHttpProxy;
    }

    @RequestMapping(
        value = {"/wallet", "/wallet/**"},
        method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
                  RequestMethod.PATCH, RequestMethod.DELETE, RequestMethod.HEAD})
    public ResponseEntity<byte[]> proxy(HttpServletRequest request) throws IOException {
        return walletServiceHttpProxy.forward(request);
    }
}
