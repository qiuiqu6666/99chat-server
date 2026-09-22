/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.wallet;

import com.chat99.server.wallet.RedPacketService;
import com.chat99.server.wallet.RedPacketStatus;
import com.chat99.server.wallet.WalletRedPacket;
import com.chat99.server.wallet.WalletRedPacketRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnWalletJobs
public class RedPacketExpireJob {
    private final WalletRedPacketRepository packetRepository;
    private final RedPacketService redPacketService;

    public RedPacketExpireJob(WalletRedPacketRepository packetRepository, RedPacketService redPacketService) {
        this.packetRepository = packetRepository;
        this.redPacketService = redPacketService;
    }

    @EventListener(value={ApplicationReadyEvent.class})
    public void expireOnStartup() {
        this.expire();
    }

    @Scheduled(fixedDelayString="${chat99.wallet.red-packet-expire-scan-interval-ms:300000}")
    public void expire() {
        List<WalletRedPacket> expired = this.packetRepository.findActiveExpiredPackets(RedPacketStatus.ACTIVE, Instant.now());
        for (WalletRedPacket p : expired) {
            this.redPacketService.expireRefund(p);
        }
    }
}
