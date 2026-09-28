package com.chat99.server.wallet;

import com.chat99.server.im.ImUserIdService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * IM BeforeSend：拦截伪造 / 重放的钱包自定义卡片（代码直接 sendMessage 同样生效）。
 */
@Service
public class WalletOrderCardSendGuardService {

    private final ObjectMapper json;

    public WalletOrderCardSendGuardService(WalletOrderCardGuardProperties props,
                                           WalletRedPacketRepository redPacketRepository,
                                           WalletTransferRepository transferRepository,
                                           ImUserIdService imUserIdService,
                                           StringRedisTemplate redis,
                                           ObjectMapper json) {
        // Keep the existing constructor contract during rollout. Legacy switches,
        // sender allowlists and Redis deduplication no longer grant card permission.
        this.json = json;
    }

    public boolean isEnabled() {
        return true;
    }

    /**
     * @return 拒绝码；empty 表示放行或不适用
     */
    public Optional<String> evaluate(Map<String, Object> body) {
        // BeforeSend is skipped only by the authenticated server REST sender.
        // Neither an existing order, an admin-looking sender nor a payload flag
        // grants an SDK client permission to create a financial card.
        return WalletOrderCardImSupport.extractCards(body, json).isEmpty()
            ? Optional.empty() : Optional.of("WALLET_CARD_SERVER_MANAGED");
    }

}
