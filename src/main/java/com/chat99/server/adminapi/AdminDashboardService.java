package com.chat99.server.adminapi;

import com.chat99.server.complaint.ChatComplaint;
import com.chat99.server.complaint.ChatComplaintRepository;
import com.chat99.server.feedback.FeedbackStatus;
import com.chat99.server.feedback.UserFeedback;
import com.chat99.server.feedback.UserFeedbackRepository;
import com.chat99.server.group.GroupSettingsRepository;
import com.chat99.server.lifepayment.LifePaymentEnums.TaskStatus;
import com.chat99.server.lifepayment.LifePaymentTaskRepository;
import com.chat99.server.user.LoginLog;
import com.chat99.server.user.LoginLogRepository;
import com.chat99.server.user.User;
import com.chat99.server.user.UserRepository;
import com.chat99.server.wallet.UserWalletRepository;
import com.chat99.server.wallet.WalletDepositRepository;
import com.chat99.server.wallet.WalletRedPacketRepository;
import com.chat99.server.wallet.WalletTransferRepository;
import com.chat99.server.wallet.WalletWithdrawalRepository;
import com.chat99.server.wallet.WithdrawalStatus;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminDashboardService {

    static final ZoneId ZONE = AdminDashboardCounterService.ZONE;

    private final UserRepository userRepository;
    private final LoginLogRepository loginLogRepository;
    private final GroupSettingsRepository groupSettingsRepository;
    private final WalletDepositRepository depositRepository;
    private final WalletWithdrawalRepository withdrawalRepository;
    private final WalletRedPacketRepository redPacketRepository;
    private final WalletTransferRepository transferRepository;
    private final UserWalletRepository userWalletRepository;
    private final AdminDashboardDailyRepository dailyRepository;
    private final AdminApiProperties adminProps;
    private final ChatComplaintRepository chatComplaintRepository;
    private final UserFeedbackRepository userFeedbackRepository;
    private final LifePaymentTaskRepository lifePaymentTaskRepository;

    public AdminDashboardService(
        UserRepository userRepository,
        LoginLogRepository loginLogRepository,
        GroupSettingsRepository groupSettingsRepository,
        WalletDepositRepository depositRepository,
        WalletWithdrawalRepository withdrawalRepository,
        WalletRedPacketRepository redPacketRepository,
        WalletTransferRepository transferRepository,
        UserWalletRepository userWalletRepository,
        AdminDashboardDailyRepository dailyRepository,
        AdminApiProperties adminProps,
        ChatComplaintRepository chatComplaintRepository,
        UserFeedbackRepository userFeedbackRepository,
        LifePaymentTaskRepository lifePaymentTaskRepository) {
        this.userRepository = userRepository;
        this.loginLogRepository = loginLogRepository;
        this.groupSettingsRepository = groupSettingsRepository;
        this.depositRepository = depositRepository;
        this.withdrawalRepository = withdrawalRepository;
        this.redPacketRepository = redPacketRepository;
        this.transferRepository = transferRepository;
        this.userWalletRepository = userWalletRepository;
        this.dailyRepository = dailyRepository;
        this.adminProps = adminProps;
        this.chatComplaintRepository = chatComplaintRepository;
        this.userFeedbackRepository = userFeedbackRepository;
        this.lifePaymentTaskRepository = lifePaymentTaskRepository;
    }

    @Transactional(readOnly = true)
    public OverviewResponse overview() {
        LocalDate today = AdminDashboardCounterService.today();
        Instant dayStart = AdminDashboardCounterService.startOfDay(today);
        Instant dayEnd = AdminDashboardCounterService.startOfNextDay(today);
        Instant onlineSince = Instant.now().minus(adminProps.onlineThresholdMinutes(), java.time.temporal.ChronoUnit.MINUTES);

        long loginToday = loginLogRepository.countDistinctSuccessfulLoginsBetween(dayStart, dayEnd);
        AdminDashboardDaily daily = dailyRepository.findById(today).orElse(null);
        long c2cMessages = daily == null ? 0 : daily.getC2cMessageCount();
        long groupMessages = daily == null ? 0 : daily.getGroupMessageCount();
        long messageToday = c2cMessages + groupMessages;

        long failedLogins = loginLogRepository.countFailedLoginsBetween(dayStart, dayEnd);
        long failedWithdrawals = withdrawalRepository.countFailedBetween(dayStart, dayEnd);
        long trackedErrors = daily == null ? 0 : daily.getSystemErrorCount();
        long systemErrors = failedLogins + failedWithdrawals + trackedErrors;

        long groupCreatedToday = daily == null
            ? groupSettingsRepository.countByCreatedAtGreaterThanEqual(dayStart)
            : Math.max(daily.getGroupsCreatedCount(),
                groupSettingsRepository.countByCreatedAtGreaterThanEqual(dayStart));

        long pendingFrozenMicro = userWalletRepository.sumPendingWithdrawMicro(
            List.of(WithdrawalStatus.PENDING, WithdrawalStatus.BROADCASTING, WithdrawalStatus.CONFIRMING));
        long usdtTotalMicro = userWalletRepository.sumBalanceUsdtMicro();
        long usdtAvailableMicro = Math.max(0L, usdtTotalMicro - pendingFrozenMicro);
        long cnyTotalFen = userWalletRepository.sumBalancePlatformFen();
        long trxTotalSun = userWalletRepository.sumBalanceTrxSun();

        long pendingComplaints = chatComplaintRepository.count(
            (Specification<ChatComplaint>) (root, query, cb) ->
                cb.equal(root.get("status"), FeedbackStatus.PENDING));
        long pendingFeedback = userFeedbackRepository.count(
            (Specification<UserFeedback>) (root, query, cb) ->
                cb.equal(root.get("status"), FeedbackStatus.PENDING));
        long lifePaymentAbnormal = lifePaymentTaskRepository.countByStatusIn(
            List.of(TaskStatus.failed, TaskStatus.need_manual));

        DashboardStats stats = new DashboardStats(
            userRepository.count(),
            userRepository.countByCreatedAtGreaterThanEqual(dayStart),
            loginToday,
            loginToday,
            userRepository.countByLastActiveAtGreaterThanEqual(onlineSince),
            Math.max(groupSettingsRepository.count(), redPacketRepository.countDistinctGroupIds()),
            groupCreatedToday,
            messageToday,
            c2cMessages,
            groupMessages,
            AdminUserFormats.decimalFromMicro(depositRepository.sumCreditedAmountMicroBetween(dayStart, dayEnd)),
            AdminUserFormats.decimalFromMicro(withdrawalRepository.sumCompletedAmountMicroBetween(dayStart, dayEnd)),
            withdrawalRepository.countByStatus(WithdrawalStatus.PENDING),
            AdminUserFormats.decimalFromMicro(redPacketRepository.sumTotalAmountBetween(dayStart, dayEnd)),
            AdminUserFormats.decimalFromMicro(transferRepository.sumCompletedAmountBetween(dayStart, dayEnd)),
            systemErrors,
            AdminUserFormats.decimalFromMicro(usdtAvailableMicro),
            AdminUserFormats.decimalFromMicro(pendingFrozenMicro),
            pendingComplaints,
            pendingFeedback,
            lifePaymentAbnormal);

        List<SiteWalletFundItem> siteWalletFunds = List.of(
            siteFund("USDT", "USDT",
                AdminUserFormats.decimalFromMicro(usdtAvailableMicro),
                AdminUserFormats.decimalFromMicro(pendingFrozenMicro),
                AdminUserFormats.decimalFromMicro(usdtTotalMicro)),
            siteFund("CNY", "CNY（人民币）",
                AdminUserFormats.decimalFromFen(cnyTotalFen),
                AdminUserFormats.decimalFromFen(0),
                AdminUserFormats.decimalFromFen(cnyTotalFen)),
            siteFund("TRX", "TRX",
                AdminUserFormats.decimalFromTrxSun(trxTotalSun),
                AdminUserFormats.decimalFromTrxSun(0),
                AdminUserFormats.decimalFromTrxSun(trxTotalSun)));

        return new OverviewResponse(stats, siteWalletFunds, defaultLabels());
    }

    private static SiteWalletFundItem siteFund(
            String currency, String label, String available, String frozen, String total) {
        return new SiteWalletFundItem(currency, label, available, frozen, total);
    }

    @Transactional(readOnly = true)
    public DailyMetricsResponse dailyMetrics(int days, int page, int pageSize) {
        int span = Math.min(Math.max(days, 1), 90);
        LocalDate today = AdminDashboardCounterService.today();
        LocalDate fromDate = today.minusDays(span - 1L);

        Map<LocalDate, DailyAccumulator> acc = new TreeMap<>();
        for (LocalDate d = fromDate; !d.isAfter(today); d = d.plusDays(1)) {
            acc.put(d, new DailyAccumulator());
        }

        Instant rangeStart = AdminDashboardCounterService.startOfDay(fromDate);
        for (User user : userRepository.findByCreatedAtGreaterThanEqual(rangeStart)) {
            LocalDate d = LocalDate.ofInstant(user.getCreatedAt(), ZONE);
            DailyAccumulator row = acc.get(d);
            if (row != null) {
                row.registeredCount++;
            }
        }

        for (LoginLog log : loginLogRepository.findBySuccessTrueAndCreatedAtGreaterThanEqual(rangeStart)) {
            if (log.getCreatedAt() == null || log.getUserId() == null || log.getUserId().isBlank()) {
                continue;
            }
            LocalDate d = LocalDate.ofInstant(log.getCreatedAt(), ZONE);
            DailyAccumulator row = acc.get(d);
            if (row != null) {
                row.loginUsers.add(log.getUserId());
            }
        }

        for (AdminDashboardDaily daily : dailyRepository.findByStatDateGreaterThanEqualOrderByStatDateDesc(fromDate)) {
            DailyAccumulator row = acc.get(daily.getStatDate());
            if (row != null) {
                row.groupsCreatedCount = Math.max(row.groupsCreatedCount, daily.getGroupsCreatedCount());
            }
        }

        List<DailyMetricRow> all = new ArrayList<>();
        for (Map.Entry<LocalDate, DailyAccumulator> e : acc.entrySet()) {
            DailyAccumulator row = e.getValue();
            all.add(new DailyMetricRow(
                e.getKey().toString(),
                row.registeredCount,
                row.loginUsers.size(),
                row.groupsCreatedCount,
                null,
                null));
        }
        all.sort((a, b) -> b.date().compareTo(a.date()));

        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        int fromIndex = (safePage - 1) * safeSize;
        if (fromIndex >= all.size()) {
            return new DailyMetricsResponse(List.of(), all.size());
        }
        int toIndex = Math.min(fromIndex + safeSize, all.size());
        return new DailyMetricsResponse(all.subList(fromIndex, toIndex), all.size());
    }

    private static Map<String, String> defaultLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("user_total", "用户总数");
        labels.put("registered_today", "今日新增用户");
        labels.put("active_today", "今日活跃用户");
        labels.put("online_users", "当前在线用户");
        labels.put("group_total", "群组总数");
        labels.put("group_created_today", "今日新增群组");
        labels.put("message_today", "今日消息数");
        labels.put("c2c_message_today", "单聊消息数");
        labels.put("group_message_today", "群聊消息数");
        labels.put("recharge_amount_today", "今日充值金额");
        labels.put("withdraw_amount_today", "今日提现金额");
        labels.put("pending_withdraw_count", "待审核提现");
        labels.put("red_packet_amount_today", "红包发送金额");
        labels.put("transfer_amount_today", "转账金额");
        labels.put("system_error_count", "系统错误数");
        labels.put("wallet_balance_total", "全站 USDT 可用");
        labels.put("wallet_frozen_total", "全站 USDT 冻结");
        labels.put("pending_complaint_count", "待处理投诉");
        labels.put("pending_feedback_count", "待处理反馈");
        labels.put("life_payment_abnormal_count", "缴费异常");
        return labels;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SiteWalletFundItem(
        String currency,
        String label,
        String available,
        String frozen,
        String total) {}

    private static final class DailyAccumulator {
        long registeredCount;
        final java.util.Set<String> loginUsers = new java.util.HashSet<>();
        long groupsCreatedCount;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DashboardStats(
        long userTotal,
        long registeredToday,
        long loginToday,
        long activeToday,
        long onlineUsers,
        long groupTotal,
        long groupCreatedToday,
        long messageToday,
        long c2cMessageToday,
        long groupMessageToday,
        String rechargeAmountToday,
        String withdrawAmountToday,
        long pendingWithdrawCount,
        String redPacketAmountToday,
        String transferAmountToday,
        long systemErrorCount,
        String walletBalanceTotal,
        String walletFrozenTotal,
        long pendingComplaintCount,
        long pendingFeedbackCount,
        long lifePaymentAbnormalCount) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record OverviewResponse(
        DashboardStats stats,
        List<SiteWalletFundItem> siteWalletFunds,
        Map<String, String> labels) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DailyMetricRow(
        String date,
        long registeredCount,
        long loginDistinctUsers,
        long groupsCreatedCount,
        Long peakOnlineUsers,
        Long avgOnlineUsers) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DailyMetricsResponse(List<DailyMetricRow> items, long total) {}
}
