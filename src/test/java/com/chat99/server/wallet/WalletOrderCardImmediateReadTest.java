package com.chat99.server.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.chat99.server.group.GroupAccessService;
import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImUserIdService;
import com.chat99.server.notify.PlatformWalletNoticeService;
import com.chat99.server.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WalletOrderCardImmediateReadTest {

    private static final String CLIENT_TRANSFER = "transfer_20260816_abc";
    private static final String CLIENT_PACKET = "red_packet_58b05f2a-875f-4f1d-bd32-7975357efa2a";
    private static final String IM_SENDER = "sender01ab";
    private static final String IM_PEER = "peer000001";
    private static final String IM_GROUP = "m25KMR3N5CY";

    @Mock WalletTransferRepository transferRepository;
    @Mock UserRepository userRepository;
    @Mock WalletLedgerService ledgerService;
    @Mock PayPinService payPinService;
    @Mock WalletLimitService limitService;
    @Mock WalletFeeService feeService;
    @Mock WalletPlatformStatsRepository statsRepository;
    @Mock WalletRedPacketRepository packetRepository;
    @Mock WalletRedPacketClaimRepository claimRepository;
    @Mock WalletConfigService configService;
    @Mock ImAdminClient imAdminClient;
    @Mock ImUserIdService imUserIdService;
    @Mock GroupAccessService groupAccessService;
    @Mock PlatformWalletNoticeService platformWalletNotice;
    @Mock RedPacketClaimNoticeService claimNoticeService;

    WalletOrderCardReadCache cache;
    WalletTransferService transferService;
    RedPacketService redPacketService;
    ObjectMapper mapper;

    @BeforeEach
    void setup() {
        mapper = new ObjectMapper().findAndRegisterModules();
        cache = new WalletOrderCardReadCache(null, mapper, 60);
        transferService = new WalletTransferService(
            userRepository, transferRepository, ledgerService, payPinService,
            limitService, feeService, statsRepository, cache);
        redPacketService = new RedPacketService(
            packetRepository, claimRepository, ledgerService, payPinService, limitService,
            feeService, configService, userRepository, statsRepository, imAdminClient,
            imUserIdService, groupAccessService, platformWalletNotice, claimNoticeService,
            cache, null, null);
        when(feeService.calculateFee(any(), any(), anyLong())).thenReturn(0L);
        when(userRepository.existsByUserId(anyString())).thenReturn(true);
        when(transferRepository.findById(anyLong())).thenReturn(Optional.empty());
        when(transferRepository.findByFromUserIdAndClientOrderId(anyString(), anyString()))
            .thenReturn(Optional.empty());
        when(transferRepository.findByToUserIdAndClientOrderId(anyString(), anyString()))
            .thenReturn(Optional.empty());
        when(transferRepository.findByClientOrderId(anyString())).thenReturn(List.of());
        when(packetRepository.findById(anyLong())).thenReturn(Optional.empty());
        when(packetRepository.findByPublicId(anyString())).thenReturn(Optional.empty());
        when(claimRepository.existsByPacketIdAndUserId(anyLong(), anyString())).thenReturn(false);
        when(groupAccessService.isLocalMember(anyString(), anyString())).thenReturn(false);
        when(imAdminClient.getRoleInGroup(anyString(), anyString())).thenReturn("NotMember");
        when(imUserIdService.toIm(anyString())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void transfer_afterPaySuccess_fiveImmediateLookupsNever404() {
        WalletTransfer paid = completedTransfer(88L);
        when(transferRepository.save(any(WalletTransfer.class))).thenAnswer(inv -> {
            WalletTransfer t = inv.getArgument(0);
            t.setId(88L);
            return t;
        });

        WalletTransfer out = transferService.transfer(
            IM_SENDER, IM_PEER, WalletCurrency.USDT, 500_000L, "123456", "午饭", CLIENT_TRANSFER);
        assertThat(out.getId()).isEqualTo(88L);
        cache.putTransfer(paid);

        for (int i = 0; i < 5; i++) {
            WalletTransfer byOrder = transferService.requireViewableByRef(IM_SENDER, "88");
            WalletTransfer byClient = transferService.requireViewableByRef(IM_SENDER, CLIENT_TRANSFER);
            WalletTransfer byPeer = transferService.requireViewableByClientOrderId(IM_PEER, CLIENT_TRANSFER);
            assertThat(byOrder.getId()).isEqualTo(88L);
            assertThat(byClient.getId()).isEqualTo(byOrder.getId());
            assertThat(byPeer.getId()).isEqualTo(byOrder.getId());
            assertThat(byOrder.getFromUserId()).isEqualTo(IM_SENDER);
            assertCardHasNoInvalidStatus(WalletController.TransferDetailResponse.from(byOrder));
        }
    }

    @Test
    void transferCardDto_hasIdentityAmountAndNoGroupId() throws Exception {
        WalletTransfer paid = completedTransfer(88L);
        cache.putTransfer(paid);
        WalletController.TransferDetailResponse dto =
            WalletController.TransferDetailResponse.from(
                transferService.requireViewableByRef(IM_SENDER, CLIENT_TRANSFER));
        JsonNode n = mapper.readTree(mapper.writeValueAsString(dto));
        assertThat(n.path("orderId").asText()).isEqualTo("88");
        assertThat(n.path("id").asText()).isEqualTo("88");
        assertThat(n.path("clientOrderId").asText()).isEqualTo(CLIENT_TRANSFER);
        assertThat(n.path("fromUserId").asText()).isEqualTo(IM_SENDER);
        assertThat(n.path("senderUserId").asText()).isEqualTo(IM_SENDER);
        assertThat(n.path("amount").isNumber()).isTrue();
        assertThat(n.path("amount").asLong()).isEqualTo(500_000L);
        assertThat(n.path("currency").asText()).isEqualTo("USDT");
        assertThat(n.path("memo").asText()).isEqualTo("午饭");
        assertThat(n.path("greeting").asText()).isEqualTo("午饭");
        assertThat(n.path("groupId").isMissingNode() || n.path("groupId").isNull()).isTrue();
        assertThat(n.path("status").asText()).isNotEqualTo("INVALID");
    }

    @Test
    void transfer_unknownRefIsNotFoundNotInvalid() {
        assertThatThrownBy(() -> transferService.requireViewableByRef(IM_SENDER, "not-a-number"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> {
                ResponseStatusException rse = (ResponseStatusException) ex;
                assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(rse.getReason()).isEqualTo("TRANSFER_NOT_FOUND");
            });
    }

    @Test
    void groupTransfer_afterPaySuccess_fiveLookupsMatchImIdentity() {
        WalletRedPacket paid = groupTransferPacket(368L);
        cache.putPacket(paid);

        for (int i = 0; i < 5; i++) {
            WalletRedPacket byOrder = redPacketService.requireReadablePacket("368", IM_SENDER);
            WalletRedPacket byClient = redPacketService.requireReadablePacket(CLIENT_PACKET, IM_PEER);
            WalletRedPacket byMember = redPacketService.requireReadablePacket("368", "member99xx");
            assertThat(byOrder.getId()).isEqualTo(368L);
            assertThat(byClient.getId()).isEqualTo(byOrder.getId());
            assertThat(byMember.getId()).isEqualTo(byOrder.getId());
            assertThat(byOrder.getSenderUserId()).isEqualTo(IM_SENDER);
            assertThat(byOrder.getFromUserId()).isEqualTo(IM_SENDER);
            assertThat(byOrder.cardGroupId()).isEqualTo(IM_GROUP);
            assertThat(byOrder.getPacketType()).isEqualTo(RedPacketType.GROUP_TRANSFER);
            assertThat(byOrder.getAmount()).isEqualTo(1_000_000L);
            assertThat(byOrder.getCurrency()).isEqualTo(WalletCurrency.USDT);
        }
    }

    @Test
    void groupTransfer_membershipLagDoesNot403() {
        WalletRedPacket paid = groupTransferPacket(368L);
        cache.putPacket(paid);
        WalletRedPacket card = redPacketService.requireReadablePacket("368", "late-member");
        assertThat(card.getId()).isEqualTo(368L);
        assertThat(card.getPacketType()).isEqualTo(RedPacketType.GROUP_TRANSFER);
    }

    @Test
    void c2cRedPacket_groupIdEmpty_orderAndClientMatch() throws Exception {
        WalletRedPacket paid = c2cPacket(12L);
        cache.putPacket(paid);
        WalletRedPacket byOrder = redPacketService.requireReadablePacket("12", IM_SENDER);
        WalletRedPacket byClient = redPacketService.requireReadablePacket(CLIENT_PACKET, IM_PEER);
        assertThat(byOrder.getId()).isEqualTo(byClient.getId());
        assertThat(byOrder.getSenderUserId()).isEqualTo(IM_SENDER);
        assertThat(byOrder.cardGroupId()).isNull();
        JsonNode n = mapper.readTree(mapper.writeValueAsString(byOrder));
        assertThat(n.path("groupId").isMissingNode() || n.path("groupId").isNull()
            || n.path("groupId").asText().isEmpty()).isTrue();
        assertThat(n.path("fromUserId").asText()).isEqualTo(IM_SENDER);
    }

    @Test
    void replicaLag_dbMissCacheHit_returnsProcessingOrCompletedCard() {
        WalletRedPacket inflight = groupTransferPacket(368L);
        inflight.setStatus(RedPacketStatus.PROCESSING);
        cache.putPacket(inflight);
        WalletRedPacket card = redPacketService.requireReadablePacket("368", IM_SENDER);
        assertThat(card.getStatus()).isIn(RedPacketStatus.PROCESSING, RedPacketStatus.PENDING,
            RedPacketStatus.COMPLETED);
        assertThat(card.getSenderUserId()).isEqualTo(IM_SENDER);
        assertThat(card.cardGroupId()).isEqualTo(IM_GROUP);
    }

    private static void assertCardHasNoInvalidStatus(WalletController.TransferDetailResponse dto) {
        assertThat(dto.status()).isNotEqualTo("INVALID");
        assertThat(dto.status()).isNotBlank();
        assertThat(dto.senderUserId()).isEqualTo(IM_SENDER);
        assertThat(dto.fromUserId()).isEqualTo(IM_SENDER);
        assertThat(dto.amount()).isEqualTo(500_000L);
    }

    private static WalletTransfer completedTransfer(long id) {
        WalletTransfer t = new WalletTransfer();
        t.setId(id);
        t.setFromUserId(IM_SENDER);
        t.setToUserId(IM_PEER);
        t.setCurrency(WalletCurrency.USDT);
        t.setAmount(500_000L);
        t.setFeeAmount(0);
        t.setMemo("午饭");
        t.setClientOrderId(CLIENT_TRANSFER);
        t.setStatus(WalletTransferStatus.COMPLETED);
        t.setCreatedAt(Instant.parse("2026-08-16T00:00:00Z"));
        return t;
    }

    private static WalletRedPacket groupTransferPacket(long id) {
        WalletRedPacket p = new WalletRedPacket();
        p.setId(id);
        p.setPublicId(CLIENT_PACKET);
        p.setSenderUserId(IM_SENDER);
        p.setPacketType(RedPacketType.GROUP_TRANSFER);
        p.setConversationType("GROUP");
        p.setGroupId(IM_GROUP);
        p.setExclusiveUserId(IM_PEER);
        p.setCurrency(WalletCurrency.USDT);
        p.setTotalAmount(1_000_000L);
        p.setPacketCount(1);
        p.setRemainingAmount(0);
        p.setRemainingCount(0);
        p.setStatus(RedPacketStatus.COMPLETED);
        p.setGreeting("群转账");
        p.setCreatedAt(Instant.parse("2026-08-16T00:00:00Z"));
        return p;
    }

    private static WalletRedPacket c2cPacket(long id) {
        WalletRedPacket p = new WalletRedPacket();
        p.setId(id);
        p.setPublicId(CLIENT_PACKET);
        p.setSenderUserId(IM_SENDER);
        p.setPacketType(RedPacketType.NORMAL_C2C);
        p.setConversationType("C2C");
        p.setGroupId("should-not-leak");
        p.setExclusiveUserId(IM_PEER);
        p.setCurrency(WalletCurrency.USDT);
        p.setTotalAmount(200_000L);
        p.setPacketCount(1);
        p.setRemainingAmount(0);
        p.setRemainingCount(0);
        p.setStatus(RedPacketStatus.COMPLETED);
        p.setCreatedAt(Instant.parse("2026-08-16T00:00:00Z"));
        return p;
    }
}
