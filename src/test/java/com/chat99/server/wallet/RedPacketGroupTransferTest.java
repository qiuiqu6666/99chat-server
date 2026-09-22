package com.chat99.server.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.group.GroupAccessService;
import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImUserIdService;
import com.chat99.server.notify.PlatformWalletNoticeService;
import com.chat99.server.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedPacketGroupTransferTest {

    @Mock WalletRedPacketRepository packetRepository;
    @Mock WalletRedPacketClaimRepository claimRepository;
    @Mock WalletLedgerService ledgerService;
    @Mock PayPinService payPinService;
    @Mock WalletLimitService limitService;
    @Mock WalletFeeService feeService;
    @Mock WalletConfigService configService;
    @Mock UserRepository userRepository;
    @Mock WalletPlatformStatsRepository statsRepository;
    @Mock ImAdminClient imAdminClient;
    @Mock ImUserIdService imUserIdService;
    @Mock GroupAccessService groupAccessService;
    @Mock PlatformWalletNoticeService platformWalletNotice;
    @Mock RedPacketClaimNoticeService claimNoticeService;
    @Mock WalletOrderCardReadCache cardReadCache;

    RedPacketService service;

    @BeforeEach
    void setup() {
        service = new RedPacketService(
            packetRepository, claimRepository, ledgerService, payPinService, limitService,
            feeService, configService, userRepository, statsRepository, imAdminClient,
            imUserIdService, groupAccessService, platformWalletNotice, claimNoticeService,
            cardReadCache, null, null);
        when(feeService.calculateFee(any(), any(), anyLong())).thenReturn(0L);
        when(packetRepository.save(any(WalletRedPacket.class))).thenAnswer(inv -> {
            WalletRedPacket p = inv.getArgument(0);
            if (p.getId() == null) {
                p.setId(100L);
            }
            return p;
        });
        when(claimRepository.save(any(WalletRedPacketClaim.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void send_groupTransfer_directCredit_whenBothLocalMembers() {
        when(groupAccessService.isLocalMember("g1", "sender")).thenReturn(true);
        when(groupAccessService.isLocalMember("g1", "peer")).thenReturn(true);
        when(userRepository.existsByUserId("peer")).thenReturn(true);

        WalletRedPacket out = service.send(
            "sender", RedPacketType.GROUP_TRANSFER, "GROUP", "g1", "peer",
            WalletCurrency.USDT, 1_000_000L, null, 1, "hi", "123456");

        assertThat(out.getStatus()).isEqualTo(RedPacketStatus.COMPLETED);
        assertThat(out.getPacketType()).isEqualTo(RedPacketType.GROUP_TRANSFER);
        assertThat(out.getRemainingCount()).isZero();
        verify(imAdminClient, never()).getRoleInGroup(anyString(), anyString());
        verify(ledgerService).debit(eq("sender"), eq(WalletCurrency.USDT), eq(1_000_000L),
            eq(WalletLedgerType.RED_PACKET_SEND), eq("RED_PACKET"), eq(100L), eq("peer"), eq("hi"));
        verify(ledgerService).credit(eq("peer"), eq(WalletCurrency.USDT), eq(1_000_000L),
            eq(WalletLedgerType.RED_PACKET_RECEIVE), eq("RED_PACKET"), eq(100L), eq("sender"), eq(null));
    }

    @Test
    void send_groupTransfer_rejectsWhenRecipientNotLocalMember() {
        when(groupAccessService.isLocalMember("g1", "sender")).thenReturn(true);
        when(groupAccessService.isLocalMember("g1", "peer")).thenReturn(false);

        assertThatThrownBy(() -> service.send(
            "sender", RedPacketType.GROUP_TRANSFER, "GROUP", "g1", "peer",
            WalletCurrency.USDT, 1_000_000L, null, 1, null, "123456"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(ex -> ((ResponseStatusException) ex).getReason())
            .isEqualTo("NOT_GROUP_MEMBER");
        verify(packetRepository, never()).save(any());
    }

    @Test
    void send_groupTransfer_rejectsNonGroupConversation() {
        assertThatThrownBy(() -> service.send(
            "sender", RedPacketType.GROUP_TRANSFER, "C2C", null, "peer",
            WalletCurrency.USDT, 1_000_000L, null, 1, null, "123456"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void claimUi_groupTransfer_isEmptyNotCanOpen() {
        WalletRedPacket packet = new WalletRedPacket();
        packet.setId(1L);
        packet.setPacketType(RedPacketType.GROUP_TRANSFER);
        packet.setStatus(RedPacketStatus.COMPLETED);
        packet.setRemainingCount(0);
        when(claimRepository.existsByPacketIdAndUserId(1L, "peer")).thenReturn(true);

        assertThat(service.resolveClaimUiState(packet, "peer")).isEqualTo(RedPacketClaimUiState.RECEIVED);
        when(claimRepository.existsByPacketIdAndUserId(1L, "other")).thenReturn(false);
        assertThat(service.resolveClaimUiState(packet, "other")).isEqualTo(RedPacketClaimUiState.EMPTY);
    }

    @Test
    void claimNotice_excludesGroupTransfer() {
        WalletRedPacket packet = new WalletRedPacket();
        packet.setPacketType(RedPacketType.GROUP_TRANSFER);
        packet.setConversationType("GROUP");
        assertThat(RedPacketClaimNoticeService.isGroupGrabbable(packet)).isFalse();
    }

    @Test
    void ledgerAssembler_setsDisplayTitleForGroupTransfer() {
        WalletPartyNameResolver partyNameResolver =
            org.mockito.Mockito.mock(WalletPartyNameResolver.class);
        org.mockito.Mockito.when(partyNameResolver.resolveProfiles(any()))
            .thenReturn(java.util.Map.of(
                "sender", new WalletPartyNameResolver.PartyProfile("发送人甲", "https://a.example/s.png"),
                "peer", new WalletPartyNameResolver.PartyProfile("收款人乙", "https://a.example/r.png")));
        WalletGroupInfoResolver groupInfoResolver =
            org.mockito.Mockito.mock(WalletGroupInfoResolver.class);
        org.mockito.Mockito.when(groupInfoResolver.resolveGroups(any()))
            .thenReturn(java.util.Map.of("g1",
                new WalletGroupInfoResolver.GroupBrief("g1", "测试群", "https://a.example/g.png")));
        WalletLedgerRecordAssembler assembler = new WalletLedgerRecordAssembler(
            org.mockito.Mockito.mock(WalletDepositRepository.class),
            org.mockito.Mockito.mock(WalletWithdrawalRepository.class),
            org.mockito.Mockito.mock(WalletTransferRepository.class),
            packetRepository,
            org.mockito.Mockito.mock(WalletExchangeOrderRepository.class),
            partyNameResolver,
            groupInfoResolver);

        WalletRedPacket rp = new WalletRedPacket();
        rp.setId(10L);
        rp.setPacketType(RedPacketType.GROUP_TRANSFER);
        rp.setStatus(RedPacketStatus.COMPLETED);
        rp.setSenderUserId("sender");
        rp.setExclusiveUserId("peer");
        rp.setGroupId("g1");
        rp.setTotalAmount(100L);
        rp.setPacketCount(1);
        rp.setRemainingCount(0);
        when(packetRepository.findAllById(any())).thenReturn(java.util.List.of(rp));

        WalletLedger ledger = new WalletLedger();
        ledger.setId(1L);
        ledger.setUserId("sender");
        ledger.setCurrency(WalletCurrency.USDT);
        ledger.setAmount(-100L);
        ledger.setBalanceAfter(0L);
        ledger.setLedgerType(WalletLedgerType.RED_PACKET_SEND);
        ledger.setRefType("RED_PACKET");
        ledger.setRefId(10L);
        ledger.setCreatedAt(java.time.Instant.parse("2026-08-13T00:00:00Z"));

        WalletLedgerRecord record = assembler.assemble(java.util.List.of(ledger)).get(0);
        assertThat(record.packetType()).isEqualTo("GROUP_TRANSFER");
        assertThat(record.displayTitle()).isEqualTo("群转账");
        assertThat(record.receiverUserId()).isEqualTo("peer");
        assertThat(record.toUserId()).isEqualTo("peer");
        assertThat(record.receiverName()).isEqualTo("收款人乙");
        assertThat(record.toUserName()).isEqualTo("收款人乙");
        assertThat(record.senderName()).isEqualTo("发送人甲");
        assertThat(record.senderUserId()).isEqualTo("sender");
        assertThat(record.senderAvatar()).isEqualTo("https://a.example/s.png");
        assertThat(record.receiverAvatar()).isEqualTo("https://a.example/r.png");
        assertThat(record.groupId()).isEqualTo("g1");
        assertThat(record.groupName()).isEqualTo("测试群");
        assertThat(record.groupAvatar()).isEqualTo("https://a.example/g.png");
    }

    @Test
    void ledgerAssembler_normalizesPacketTypeAndGroupFields() {
        WalletPartyNameResolver partyNameResolver =
            org.mockito.Mockito.mock(WalletPartyNameResolver.class);
        org.mockito.Mockito.when(partyNameResolver.resolveProfiles(any()))
            .thenReturn(java.util.Map.of(
                "sender", new WalletPartyNameResolver.PartyProfile("发起人", "https://a.example/s.png"),
                "peer", new WalletPartyNameResolver.PartyProfile("收款人", "https://a.example/r.png")));
        WalletGroupInfoResolver groupInfoResolver =
            org.mockito.Mockito.mock(WalletGroupInfoResolver.class);
        org.mockito.Mockito.when(groupInfoResolver.resolveGroups(any()))
            .thenReturn(java.util.Map.of("g9",
                new WalletGroupInfoResolver.GroupBrief("g9", "拼手气群", "https://a.example/g9.png")));
        WalletLedgerRecordAssembler assembler = new WalletLedgerRecordAssembler(
            org.mockito.Mockito.mock(WalletDepositRepository.class),
            org.mockito.Mockito.mock(WalletWithdrawalRepository.class),
            org.mockito.Mockito.mock(WalletTransferRepository.class),
            packetRepository,
            org.mockito.Mockito.mock(WalletExchangeOrderRepository.class),
            partyNameResolver,
            groupInfoResolver);

        // 群拼手气：packetType → LUCKY；groupId/groupAvatar 用于群聊展示
        WalletRedPacket lucky = new WalletRedPacket();
        lucky.setId(20L);
        lucky.setPacketType(RedPacketType.LUCKY_GROUP);
        lucky.setStatus(RedPacketStatus.COMPLETED);
        lucky.setSenderUserId("sender");
        lucky.setGroupId("g9");
        lucky.setTotalAmount(500L);
        lucky.setPacketCount(5);
        lucky.setRemainingCount(0);

        // 单聊普通红包：packetType → COMMON；groupId 恒为 null（非群聊）
        WalletRedPacket c2c = new WalletRedPacket();
        c2c.setId(21L);
        c2c.setPacketType(RedPacketType.NORMAL_C2C);
        c2c.setStatus(RedPacketStatus.COMPLETED);
        c2c.setSenderUserId("sender");
        c2c.setExclusiveUserId("peer");
        c2c.setConversationType("C2C");
        c2c.setTotalAmount(300L);
        c2c.setPacketCount(1);
        c2c.setRemainingCount(0);

        when(packetRepository.findAllById(any())).thenReturn(java.util.List.of(lucky, c2c));

        WalletLedgerRecord luckyRecord = assembler.assemble(
            java.util.List.of(redPacketLedger(20L, WalletLedgerType.RED_PACKET_SEND, -500L))).get(0);
        assertThat(luckyRecord.packetType()).isEqualTo("LUCKY");
        assertThat(luckyRecord.groupId()).isEqualTo("g9");
        assertThat(luckyRecord.groupName()).isEqualTo("拼手气群");
        assertThat(luckyRecord.groupAvatar()).isEqualTo("https://a.example/g9.png");
        assertThat(luckyRecord.senderAvatar()).isEqualTo("https://a.example/s.png");
        assertThat(luckyRecord.receiverUserId()).isNull();

        WalletLedgerRecord c2cRecord = assembler.assemble(
            java.util.List.of(redPacketLedger(21L, WalletLedgerType.RED_PACKET_RECEIVE, 300L))).get(0);
        assertThat(c2cRecord.packetType()).isEqualTo("COMMON");
        assertThat(c2cRecord.groupId()).isNull();
        assertThat(c2cRecord.groupAvatar()).isNull();
        assertThat(c2cRecord.senderAvatar()).isEqualTo("https://a.example/s.png");
        assertThat(c2cRecord.receiverUserId()).isEqualTo("peer");
        assertThat(c2cRecord.receiverAvatar()).isEqualTo("https://a.example/r.png");
    }

    private static WalletLedger redPacketLedger(long refId, WalletLedgerType type, long amount) {
        WalletLedger ledger = new WalletLedger();
        ledger.setId(refId);
        ledger.setUserId("sender");
        ledger.setCurrency(WalletCurrency.USDT);
        ledger.setAmount(amount);
        ledger.setBalanceAfter(0L);
        ledger.setLedgerType(type);
        ledger.setRefType("RED_PACKET");
        ledger.setRefId(refId);
        ledger.setCreatedAt(java.time.Instant.parse("2026-08-13T00:00:00Z"));
        return ledger;
    }
}
