package com.chat99.server.wallet;

import com.chat99.server.common.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@ConditionalOnProperty(name = "wallet.proxy-enabled", havingValue = "false", matchIfMissing = true)
@RestController
@RequestMapping("/wallet")
public class WalletController {

    private static final Logger log = LoggerFactory.getLogger(WalletController.class);

    private final WalletAccountService accountService;
    private final PayPinService payPinService;
    private final WalletDepositRepository depositRepository;
    private final WalletLedgerRepository ledgerRepository;
    private final WalletExchangeService exchangeService;
    private final WalletTransferService transferService;
    private final RedPacketService redPacketService;
    private final WithdrawService withdrawService;
    private final WalletWithdrawalRepository withdrawalRepository;
    private final WalletRedPacketRepository redPacketRepository;
    private final WalletRedPacketClaimRepository redPacketClaimRepository;
    private final WalletTransferRepository transferRepository;
    private final WalletExchangeOrderRepository exchangeOrderRepository;
    private final ExchangeRateService exchangeRateService;
    private final DepositHotAddressService depositHotAddressService;
    private final DepositScanService depositScanService;
    private final WalletCurrencyCatalogService currencyCatalogService;
    private final WalletLedgerRecordAssembler ledgerRecordAssembler;
    private final WalletPartyNameResolver partyNameResolver;
    private final WalletGroupInfoResolver groupInfoResolver;

    public WalletController(WalletAccountService accountService, PayPinService payPinService,
                            WalletDepositRepository depositRepository, WalletLedgerRepository ledgerRepository,
                            WalletExchangeService exchangeService, WalletTransferService transferService,
                            RedPacketService redPacketService, WithdrawService withdrawService,
                            WalletWithdrawalRepository withdrawalRepository,
                            WalletRedPacketRepository redPacketRepository,
                            WalletRedPacketClaimRepository redPacketClaimRepository,
                            WalletTransferRepository transferRepository,
                            WalletExchangeOrderRepository exchangeOrderRepository,
                            ExchangeRateService exchangeRateService,
                            DepositHotAddressService depositHotAddressService,
                            DepositScanService depositScanService,
                            WalletCurrencyCatalogService currencyCatalogService,
                            WalletLedgerRecordAssembler ledgerRecordAssembler,
                            WalletPartyNameResolver partyNameResolver,
                            WalletGroupInfoResolver groupInfoResolver) {
        this.accountService = accountService;
        this.payPinService = payPinService;
        this.depositRepository = depositRepository;
        this.ledgerRepository = ledgerRepository;
        this.exchangeService = exchangeService;
        this.transferService = transferService;
        this.redPacketService = redPacketService;
        this.withdrawService = withdrawService;
        this.withdrawalRepository = withdrawalRepository;
        this.redPacketRepository = redPacketRepository;
        this.redPacketClaimRepository = redPacketClaimRepository;
        this.transferRepository = transferRepository;
        this.exchangeOrderRepository = exchangeOrderRepository;
        this.exchangeRateService = exchangeRateService;
        this.depositHotAddressService = depositHotAddressService;
        this.depositScanService = depositScanService;
        this.currencyCatalogService = currencyCatalogService;
        this.ledgerRecordAssembler = ledgerRecordAssembler;
        this.partyNameResolver = partyNameResolver;
        this.groupInfoResolver = groupInfoResolver;
    }

    /**
     * 红包/群转账详情：packet 内含 toUserId/receiverUserId/fromUserId 等（packetType 为原始枚举名），
     * 顶层为统一口径字段（packetType=COMMON|LUCKY|EXCLUSIVE|GROUP_TRANSFER）；
     * 昵称头像来自数据库 users 表，群资料来自 group_profile 表。
     */
    public record RedPacketDetailResponse(
        WalletRedPacket packet,
        List<RedPacketClaimRecord> claims,
        RedPacketClaimUiState claimState,
        Long myClaimAmount,
        /** 统一类型口径：COMMON|LUCKY|EXCLUSIVE|GROUP_TRANSFER */
        String packetType,
        /** 发送人用户ID */
        String senderUserId,
        /** 发送人昵称 */
        String senderName,
        /** 发送人头像 URL（数据库 users 表） */
        String senderAvatar,
        /** 收款人用户ID（定向红包/群转账；与 packet.toUserId 一致） */
        String receiverUserId,
        /** 收款人昵称（数据库 users 表；查不到为 null） */
        String receiverName,
        /** 收款人头像 URL（数据库 users 表） */
        String receiverAvatar,
        /** 收款人昵称（与 receiverName 一致，历史字段兼容） */
        String toUserName,
        /** 群ID（群红包/群转账；非空即群聊，前端优先显示群头像） */
        String groupId,
        /** 群名称（数据库 group_profile 表） */
        String groupName,
        /** 群头像 URL（数据库 group_profile 表，无自定义头像时为默认群头像） */
        String groupAvatar) {}

    public record PayPinSetRequest(@NotBlank String payPin) {}
    public record PayPinChangeRequest(@NotBlank String oldPayPin, @NotBlank String newPayPin) {}

    public record PayPinResetRequest(@NotBlank String smsCode, @NotBlank String payPin) {}

    public record BalancesDto(long usdtMicro, long platformFen) {}

    /** 1 USDT 兑人民币；buy=USDT→平台币(99)参考价，sell=平台币→USDT参考价。 */
    public record UsdtPriceDto(
        double cnyPerUsdt,
        double cnyPerUsdtBuy,
        double cnyPerUsdtSell,
        String quoteCurrency,
        java.time.Instant updatedAt) {}

    public record MeWalletResponse(
        String depositAddress,
        String usdtContract,
        String minDepositUsdt,
        BalancesDto balances,
        UsdtPriceDto usdtPrice,
        String platformCurrency,
        boolean payPinSet) {}

    public record ExchangeRequest(
        @NotNull ExchangeDirection direction,
        @NotNull Long amount,
        @NotBlank String payPin) {}

    public record TransferRequest(
        @NotBlank String toUserId,
        @NotNull WalletCurrency currency,
        @NotNull Long amount,
        @NotBlank String payPin,
        String memo,
        String clientOrderId) {}

    /** 转账查单（与客户端 unknown recover / IM 卡片对齐）。 */
    public record TransferDetailResponse(
        String id,
        String orderId,
        String clientOrderId,
        String status,
        WalletCurrency currency,
        long amount,
        String fromUserId,
        String senderUserId,
        String toUserId,
        String memo,
        String greeting,
        java.time.Instant createdAt) {

        static TransferDetailResponse from(WalletTransfer t) {
            String id = String.valueOf(t.getId());
            String memo = t.getMemo();
            return new TransferDetailResponse(
                id,
                id,
                t.getClientOrderId(),
                t.getStatus() != null ? t.getStatus().name() : WalletTransferStatus.COMPLETED.name(),
                t.getCurrency(),
                t.getAmount(),
                t.getFromUserId(),
                t.getFromUserId(),
                t.getToUserId(),
                memo,
                memo,
                t.getCreatedAt());
        }
    }

    public record RedPacketSendRequest(
        /** NORMAL_GROUP / LUCKY_GROUP / EXCLUSIVE / NORMAL_C2C / GROUP_TRANSFER（群转账，仅 GROUP，直接到账） */
        @NotNull RedPacketType packetType,
        @NotBlank String conversationType,
        String groupId,
        String toUserId,
        @NotNull WalletCurrency currency,
        Long totalAmount,
        Long perAmount,
        Integer packetCount,
        String greeting,
        @NotBlank String payPin,
        /** 可选；IM 卡片 ID，格式 red_packet_{uuid}，与路径查询共用 */
        String clientPacketId) {}

    public record RedPacketClaimStateResponse(
        RedPacketClaimUiState claimState,
        Long myClaimAmount,
        RedPacketStatus packetStatus,
        int remainingCount,
        /** 与详情 / IM 卡对齐，便于气泡本地校验（可不读完整详情）。 */
        Long orderId,
        String publicId,
        String clientOrderId,
        RedPacketType packetType,
        String senderUserId,
        String fromUserId,
        String groupId,
        String chatGroupId,
        String imGroupId,
        String toUserId,
        WalletCurrency currency,
        long amount,
        long totalAmount,
        String greeting,
        String displayTitle) {

        static RedPacketClaimStateResponse of(WalletRedPacket packet,
                                              RedPacketClaimUiState claimState,
                                              Long myClaimAmount) {
            String groupId = packet.cardGroupId();
            return new RedPacketClaimStateResponse(
                claimState,
                myClaimAmount,
                packet.getStatus(),
                packet.getRemainingCount(),
                packet.getId(),
                packet.getPublicId(),
                packet.getPublicId(),
                packet.getPacketType(),
                packet.getSenderUserId(),
                packet.getSenderUserId(),
                groupId,
                groupId,
                groupId,
                packet.getToUserId(),
                packet.getCurrency(),
                packet.getAmount(),
                packet.getTotalAmount(),
                packet.getGreeting(),
                packet.getDisplayTitle());
        }
    }

    public record WithdrawRequest(
        @NotBlank String toAddress,
        @NotNull Long amountMicro,
        @NotBlank String payPin,
        String clientOrderId) {}

    public record LiveActivityTokenRequest(
        String platform,
        @NotBlank String activityId,
        @NotBlank String pushToken,
        String bundleId,
        String environment) {}

    @GetMapping("/currencies")
    public WalletCurrencyCatalogService.WalletCurrenciesResponse currencies(Authentication auth) {
        return currencyCatalogService.buildForUser(userId(auth));
    }

    @GetMapping("/me")
    public MeWalletResponse me(Authentication auth) {
        String userId = userId(auth);
        UserWallet w = accountService.ensureWalletByUserId(userId);
        if (w == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "WALLET_NOT_CONFIGURED");
        }
        depositHotAddressService.markHot(w.getTronAddress());
        WalletAccountService.WalletInfo info = accountService.walletInfo(w);
        UsdtPriceDto usdtPrice = buildUsdtPrice();
        return new MeWalletResponse(
            info.depositAddress(),
            info.usdtContract(),
            info.minDepositUsdt(),
            new BalancesDto(w.getBalanceUsdtMicro(), w.getBalancePlatformFen()),
            usdtPrice,
            WalletCurrency.PLATFORM.getApiCode(),
            payPinService.isSet(userId));
    }

    private UsdtPriceDto buildUsdtPrice() {
        try {
            ExchangeRateService.UsdtPriceInfo p = exchangeRateService.usdtPriceInfo();
            return new UsdtPriceDto(
                roundPrice(p.cnyPerUsdt()),
                roundPrice(p.cnyPerUsdtBuy()),
                roundPrice(p.cnyPerUsdtSell()),
                "CNY",
                p.updatedAt());
        } catch (Exception e) {
            return null;
        }
    }

    private static double roundPrice(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }

    @PostMapping("/pay-pin/set")
    public void setPayPin(Authentication auth, @Valid @RequestBody PayPinSetRequest req) {
        payPinService.setPin(userId(auth), req.payPin());
    }

    @PostMapping("/pay-pin/change")
    public void changePayPin(Authentication auth, @Valid @RequestBody PayPinChangeRequest req) {
        payPinService.changePin(userId(auth), req.oldPayPin(), req.newPayPin());
    }

    @PostMapping("/pay-pin/reset")
    public void resetPayPin(Authentication auth, @Valid @RequestBody PayPinResetRequest req) {
        payPinService.resetWithSms(userId(auth), req.smsCode(), req.payPin());
    }

    @GetMapping("/deposits")
    public Page<WalletDeposit> deposits(Authentication auth,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        String userId = userId(auth);
        Pageable pageable = PageRequest.of(page, size);
        Page<WalletDeposit> chain = depositRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        List<WalletLedger> adminCredits = ledgerRepository.findAdminAdjustLedgers(
            userId, WalletLedgerType.ADMIN_ADJUST).stream()
            .filter(l -> l.getCurrency() == WalletCurrency.USDT && l.getAmount() > 0)
            .toList();
        if (adminCredits.isEmpty()) {
            return chain;
        }
        int fetch = Math.min(500, (page + 1) * size + adminCredits.size());
        Page<WalletDeposit> window = depositRepository.findByUserIdOrderByCreatedAtDesc(
            userId, PageRequest.of(0, fetch));
        return WalletRecordQuerySupport.mergeDeposits(window, adminCredits, pageable);
    }

    public record ReportDepositTxRequest(@NotBlank String txId) {}

    @PostMapping("/deposits/report-tx")
    public DepositScanService.DepositReportResult reportDepositTx(Authentication auth,
                                                                  @Valid @RequestBody ReportDepositTxRequest req) {
        return depositScanService.processTransferByTxId(userId(auth), req.txId().trim());
    }

    @GetMapping("/ledger")
    public PageResponse<WalletLedgerRecord> ledger(Authentication auth,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size,
                                                   @RequestParam(required = false) List<WalletLedgerType> ledgerType,
                                                   @RequestParam(required = false) LedgerSource source) {
        String userId = userId(auth);
        PageRequest pageable = PageRequest.of(page, size);
        Page<WalletLedger> pageResult = queryLedger(userId, ledgerType, source, pageable);
        List<WalletLedgerRecord> records = ledgerRecordAssembler.assemble(pageResult.getContent());
        return PageResponse.of(records, pageResult);
    }

    private Page<WalletLedger> queryLedger(String userId, List<WalletLedgerType> ledgerType,
                                           LedgerSource source, PageRequest pageable) {
        if (source != null) {
            return ledgerBySource(userId, ledgerType, source, pageable);
        }
        if (ledgerType == null || ledgerType.isEmpty()) {
            return ledgerRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        }
        if (WalletRecordQuerySupport.isWalletRecordLedgerQuery(ledgerType)) {
            return ledgerRepository.findForWalletRecordLedger(
                userId,
                WalletRecordQuerySupport.ledgerTypesForDbIn(ledgerType),
                WalletLedgerType.ADMIN_ADJUST,
                WalletLedgerType.WITHDRAW,
                WalletRecordQuerySupport.includeWithdrawRefund(ledgerType),
                pageable);
        }
        return ledgerRepository.findByUserIdAndLedgerTypeInOrderByCreatedAtDesc(
            userId, expandLedgerTypeFilter(ledgerType), pageable);
    }

    /** source=CHAIN/INTERNAL 时，按来源拆分链上充提与运营调账（分页正确）。 */
    private Page<WalletLedger> ledgerBySource(String userId, List<WalletLedgerType> ledgerType,
                                              LedgerSource source, PageRequest pageable) {
        List<WalletLedgerType> types = chainWithdrawDepositTypes(ledgerType);
        if (source == LedgerSource.CHAIN) {
            return ledgerRepository.findByUserIdAndLedgerTypeInAndRefTypeInOrderByCreatedAtDesc(
                userId, types, List.of("DEPOSIT", "WITHDRAW"), pageable);
        }
        boolean wantDeposit = types.contains(WalletLedgerType.DEPOSIT);
        boolean wantWithdraw = types.contains(WalletLedgerType.WITHDRAW);
        return ledgerRepository.findInternalAdjustLedger(
            userId, types, WalletLedgerType.ADMIN_ADJUST, wantDeposit, wantWithdraw, pageable);
    }

    /** source 仅对 DEPOSIT/WITHDRAW 生效；缺省或越界时取 {DEPOSIT, WITHDRAW}。 */
    private static List<WalletLedgerType> chainWithdrawDepositTypes(List<WalletLedgerType> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of(WalletLedgerType.DEPOSIT, WalletLedgerType.WITHDRAW);
        }
        List<WalletLedgerType> filtered = requested.stream()
            .filter(t -> t == WalletLedgerType.DEPOSIT || t == WalletLedgerType.WITHDRAW)
            .distinct()
            .toList();
        return filtered.isEmpty()
            ? List.of(WalletLedgerType.DEPOSIT, WalletLedgerType.WITHDRAW)
            : filtered;
    }

    /** 筛选 DEPOSIT/WITHDRAW 时同时包含历史 ADMIN_ADJUST 流水。 */
    private static List<WalletLedgerType> expandLedgerTypeFilter(List<WalletLedgerType> requested) {
        java.util.LinkedHashSet<WalletLedgerType> expanded = new java.util.LinkedHashSet<>(requested);
        if (requested.contains(WalletLedgerType.DEPOSIT) || requested.contains(WalletLedgerType.WITHDRAW)) {
            expanded.add(WalletLedgerType.ADMIN_ADJUST);
        }
        return List.copyOf(expanded);
    }

    @GetMapping("/transfers")
    public Page<WalletTransfer> transfers(Authentication auth,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size,
                                          @RequestParam(defaultValue = "all") String direction) {
        String userId = userId(auth);
        PageRequest pageable = PageRequest.of(page, size);
        return switch (direction.toLowerCase()) {
            case "sent" -> transferRepository.findByFromUserIdOrderByCreatedAtDesc(userId, pageable);
            case "received" -> transferRepository.findByToUserIdOrderByCreatedAtDesc(userId, pageable);
            case "all" -> transferRepository.findByUserInvolvedOrderByCreatedAtDesc(userId, pageable);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_DIRECTION");
        };
    }

    @GetMapping("/exchanges")
    public Page<WalletExchangeOrder> exchanges(Authentication auth,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return exchangeOrderRepository.findByUserIdOrderByCreatedAtDesc(userId(auth), PageRequest.of(page, size));
    }

    @PostMapping("/exchange")
    public WalletExchangeOrder exchange(Authentication auth, @Valid @RequestBody ExchangeRequest req) {
        return exchangeService.exchange(userId(auth), req.direction(), req.amount(), req.payPin());
    }

    @GetMapping("/transfer/{orderId}")
    public TransferDetailResponse transferByOrderId(Authentication auth, @PathVariable String orderId) {
        return TransferDetailResponse.from(
            transferService.requireViewableByRef(userId(auth), orderId));
    }

    @GetMapping("/transfer/by-client-id/{clientOrderId}")
    public TransferDetailResponse transferByClientOrderId(Authentication auth,
                                                          @PathVariable String clientOrderId) {
        return TransferDetailResponse.from(
            transferService.requireViewableByClientOrderId(userId(auth), clientOrderId));
    }

    @PostMapping("/transfer")
    public TransferDetailResponse transfer(Authentication auth, @Valid @RequestBody TransferRequest req) {
        WalletTransfer t = transferService.transfer(userId(auth), req.toUserId(), req.currency(),
            req.amount(), req.payPin(), req.memo(), req.clientOrderId());
        return TransferDetailResponse.from(t);
    }

    @PostMapping("/red-packet/send")
    public WalletRedPacket sendRedPacket(Authentication auth, @Valid @RequestBody RedPacketSendRequest req) {
        String senderUserId = userId(auth);
        int count = req.packetCount() == null ? 1 : req.packetCount();
        long total = req.totalAmount() == null ? 0 : req.totalAmount();
        log.info("red packet send requested sender={} type={} conversationType={} groupId={} toUserId={} currency={} total={} count={} hasClientPacketId={}",
            senderUserId, req.packetType(), req.conversationType(), req.groupId(), req.toUserId(),
            req.currency(), total, count, req.clientPacketId() != null && !req.clientPacketId().isBlank());
        try {
            return redPacketService.send(senderUserId, req.packetType(), req.conversationType(),
                req.groupId(), req.toUserId(), req.currency(), total, req.perAmount(), count,
                req.greeting(), req.payPin(), req.clientPacketId());
        } catch (RuntimeException ex) {
            log.warn("red packet send rejected sender={} type={} conversationType={} groupId={} toUserId={} code={} message={}",
                senderUserId, req.packetType(), req.conversationType(), req.groupId(), req.toUserId(),
                ex instanceof ResponseStatusException status ? status.getReason() : ex.getClass().getSimpleName(),
                ex.getMessage());
            throw ex;
        }
    }

    @PostMapping("/red-packet/{id}/claim")
    public WalletRedPacketClaim claim(Authentication auth, @PathVariable String id) {
        return redPacketService.claim(userId(auth), id);
    }

    @GetMapping("/red-packets")
    public Page redPackets(Authentication auth,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "20") int size,
                              @RequestParam(defaultValue = "sent") String role) {
        String userId = userId(auth);
        PageRequest pageable = PageRequest.of(page, size);
        return switch (role.toLowerCase()) {
            case "sent" -> redPacketRepository.findBySenderUserIdOrderByCreatedAtDesc(userId, pageable);
            case "received" -> redPacketClaimRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_ROLE");
        };
    }

    @GetMapping("/red-packet/by-client-id/{clientOrderId}")
    public RedPacketDetailResponse redPacketByClientOrderId(Authentication auth,
                                                            @PathVariable String clientOrderId) {
        return redPacketDetail(userId(auth), clientOrderId);
    }

    /** 红包详情（数字 id 或 publicId / clientOrderId；claims 与 /claims 同为富集明细） */
    @GetMapping("/red-packet/{id}")
    public RedPacketDetailResponse redPacket(Authentication auth, @PathVariable String id) {
        return redPacketDetail(userId(auth), id);
    }

    /** 聊天气泡/列表用：仅返回当前用户对该红包的领取态（数字 id 或 publicId / clientOrderId） */
    @GetMapping("/red-packet/{id}/claim-state")
    public RedPacketClaimStateResponse redPacketClaimState(Authentication auth, @PathVariable String id) {
        String userId = userId(auth);
        WalletRedPacket packet = redPacketService.requireReadablePacket(id, userId);
        return RedPacketClaimStateResponse.of(
            packet,
            redPacketService.resolveClaimUiState(packet, userId),
            redPacketService.myClaimAmount(packet, userId));
    }

    /** 领取明细（数字 id 或 publicId，拼手气详情用） */
    @GetMapping("/red-packet/{id}/claims")
    public List<RedPacketClaimRecord> redPacketClaims(Authentication auth, @PathVariable String id) {
        WalletRedPacket packet = redPacketService.requireReadablePacket(id, userId(auth));
        List<WalletRedPacketClaim> claims =
            redPacketClaimRepository.findByPacketIdOrderByCreatedAtAsc(packet.getId());
        return enrichClaims(packet, claims);
    }

    private RedPacketDetailResponse redPacketDetail(String userId, String id) {
        WalletRedPacket packet = redPacketService.requireReadablePacket(id, userId);
        List<WalletRedPacketClaim> claims = packet.getId() == null
            ? List.of()
            : redPacketClaimRepository.findByPacketIdOrderByCreatedAtAsc(packet.getId());
        RedPacketClaimUiState claimState = redPacketService.resolveClaimUiState(packet, userId);
        Long myAmount = redPacketService.myClaimAmount(packet, userId);

        // 定向红包/群转账：统一返回收款人字段（receiverUserId/receiverName + 兼容 toUserName）；
        // 昵称+头像批量取数据库 users 表（不访问 IM，快速返回）
        String receiverUserId = packet.getExclusiveUserId();
        java.util.Set<String> partyIds = new java.util.LinkedHashSet<>();
        if (packet.getSenderUserId() != null && !packet.getSenderUserId().isBlank()) {
            partyIds.add(packet.getSenderUserId().trim());
        }
        if (receiverUserId != null && !receiverUserId.isBlank()) {
            partyIds.add(receiverUserId.trim());
        }
        java.util.Map<String, WalletPartyNameResolver.PartyProfile> parties =
            partyNameResolver.resolveProfiles(partyIds);
        WalletPartyNameResolver.PartyProfile senderProfile =
            packet.getSenderUserId() == null || packet.getSenderUserId().isBlank()
                ? null : parties.get(packet.getSenderUserId().trim());
        String senderName = senderProfile == null ? null : senderProfile.nickname();
        String senderAvatar = senderProfile == null ? null : senderProfile.avatarUrl();
        WalletPartyNameResolver.PartyProfile receiverProfile =
            receiverUserId == null || receiverUserId.isBlank() ? null : parties.get(receiverUserId.trim());
        String receiverName = receiverProfile == null ? null : receiverProfile.nickname();
        String receiverAvatar = receiverProfile == null ? null : receiverProfile.avatarUrl();

        // 群会话（群红包/群转账）：补 groupId/groupName/groupAvatar（数据库 group_profile 表）
        String groupId = packet.cardGroupId();
        String groupName = null;
        String groupAvatar = null;
        if (groupId != null && !groupId.isBlank()) {
            WalletGroupInfoResolver.GroupBrief g =
                groupInfoResolver.resolveGroups(List.of(groupId)).get(groupId);
            if (g != null) {
                groupName = g.groupName();
                groupAvatar = g.groupAvatar();
            }
        }

        return new RedPacketDetailResponse(packet, enrichClaims(packet, claims), claimState, myAmount,
            packet.getPacketType() == null ? null : packet.getPacketType().apiCode(),
            packet.getSenderUserId(),
            senderName, senderAvatar,
            receiverUserId, receiverName, receiverAvatar,
            receiverName,
            groupId, groupName, groupAvatar);
    }

    /** 富集领取明细：币种来自红包，手气最佳=拼手气多份红包的最大金额，昵称头像批量取自数据库 users 表。 */
    private List<RedPacketClaimRecord> enrichClaims(WalletRedPacket packet, List<WalletRedPacketClaim> claims) {
        if (claims.isEmpty()) {
            return List.of();
        }
        String currency = packet.getCurrency() == WalletCurrency.USDT
            ? WalletCurrency.USDT.getApiCode()
            : WalletCurrency.PLATFORM.getApiCode();
        boolean luckyMultiple = packet.getPacketType() == RedPacketType.LUCKY_GROUP && packet.getPacketCount() > 1;
        long maxAmount = luckyMultiple
            ? claims.stream().mapToLong(WalletRedPacketClaim::getAmount).max().orElse(Long.MIN_VALUE)
            : Long.MIN_VALUE;

        java.util.Set<String> userIds = claims.stream()
            .map(WalletRedPacketClaim::getUserId)
            .filter(u -> u != null && !u.isBlank())
            .map(String::trim)
            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        java.util.Map<String, WalletPartyNameResolver.PartyProfile> profiles =
            partyNameResolver.resolveProfiles(userIds);

        boolean bestLuckAssigned = false;
        List<RedPacketClaimRecord> out = new java.util.ArrayList<>(claims.size());
        for (WalletRedPacketClaim c : claims) {
            boolean bestLuck = luckyMultiple && !bestLuckAssigned && c.getAmount() == maxAmount;
            if (bestLuck) {
                bestLuckAssigned = true;
            }
            WalletPartyNameResolver.PartyProfile p =
                c.getUserId() == null || c.getUserId().isBlank() ? null : profiles.get(c.getUserId().trim());
            out.add(new RedPacketClaimRecord(
                String.valueOf(c.getId()),
                c.getUserId(),
                currency,
                c.getAmount(),
                c.getCreatedAt(),
                bestLuck,
                p != null ? p.nickname() : null,
                p != null ? p.avatarUrl() : null));
        }
        return out;
    }

    @PostMapping("/withdraw")
    public WithdrawDetailResponse withdraw(Authentication auth, @Valid @RequestBody WithdrawRequest req) {
        WalletWithdrawal w = withdrawService.request(
            userId(auth), req.toAddress(), req.amountMicro(), req.payPin(), req.clientOrderId());
        return withdrawService.toDetail(w);
    }

    @GetMapping("/withdraw/by-client-id/{clientOrderId}")
    public WithdrawDetailResponse withdrawByClientOrderId(Authentication auth,
                                                          @PathVariable String clientOrderId) {
        return withdrawService.toDetail(
            withdrawService.requireViewableByClientOrderId(userId(auth), clientOrderId));
    }

    @GetMapping("/withdraw/{orderId}")
    public WithdrawDetailResponse withdrawByOrderId(Authentication auth, @PathVariable String orderId) {
        return withdrawService.toDetail(
            withdrawService.requireViewableByRef(userId(auth), orderId));
    }

    @PutMapping("/withdraw/{orderId}/live-activity-token")
    public void bindLiveActivityToken(Authentication auth,
                                      @PathVariable String orderId,
                                      @Valid @RequestBody LiveActivityTokenRequest req) {
        withdrawService.bindLiveActivityToken(
            userId(auth), orderId, req.platform(), req.activityId(), req.pushToken(),
            req.bundleId(), req.environment());
    }

    @GetMapping("/withdrawals")
    public Page<WalletWithdrawal> withdrawals(Authentication auth,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        String userId = userId(auth);
        Pageable pageable = PageRequest.of(page, size);
        Page<WalletWithdrawal> chain = withdrawalRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        List<WalletLedger> adminDebits = ledgerRepository.findAdminAdjustLedgers(
            userId, WalletLedgerType.ADMIN_ADJUST).stream()
            .filter(l -> l.getCurrency() == WalletCurrency.USDT && l.getAmount() < 0)
            .toList();
        if (adminDebits.isEmpty()) {
            return chain;
        }
        int fetch = Math.min(500, (page + 1) * size + adminDebits.size());
        Page<WalletWithdrawal> window = withdrawalRepository.findByUserIdOrderByCreatedAtDesc(
            userId, PageRequest.of(0, fetch));
        return WalletRecordQuerySupport.mergeWithdrawals(window, adminDebits, pageable);
    }

    private static String userId(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return auth.getName();
    }
}
