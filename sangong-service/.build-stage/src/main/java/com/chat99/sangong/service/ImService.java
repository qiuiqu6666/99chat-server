package com.chat99.sangong.service;

import com.chat99.sangong.config.SangongProperties;
import com.chat99.sangong.domain.SangongRound;
import com.chat99.sangong.domain.SangongUser;
import com.chat99.sangong.repository.RoundRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencentyun.TLSSigAPIv2;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * 腾讯 IM REST 客户端 + 群通知文案（与 PHP ImService 文案一致）。
 * 未配置 IM 时静默跳过（记录日志，返回 false/null）。
 */
@Service
public class ImService {
    public static final String INSUFFICIENT_BALANCE_NOTICE = "积分不足,本次下注无效";

    private static final Logger log = LoggerFactory.getLogger(ImService.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String[] CIRCLED = {"❶","❷","❸","❹","❺","❻","❼","❽","❾","❿"};

    private final GameSettingsService settings;
    private final SangongProperties props;
    private final IntegrationImService integration;
    private final RoundRepository rounds;
    private final ReportImageService reportImages;
    private final TrendChartService trendChart;
    private final AdminSettleBillService settleBill;
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    public ImService(GameSettingsService settings, SangongProperties props,
                     IntegrationImService integration, RoundRepository rounds,
                     @Lazy ReportImageService reportImages,
                     @Lazy TrendChartService trendChart,
                     @Lazy AdminSettleBillService settleBill) {
        this.settings = settings;
        this.props = props;
        this.integration = integration;
        this.rounds = rounds;
        this.reportImages = reportImages;
        this.trendChart = trendChart;
        this.settleBill = settleBill;
        this.http = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();
    }

    // ===== 基础发送 =====

    public boolean sendGameGroupText(String text) {
        return sendGameGroupText(text, null);
    }

    public boolean sendGameGroupText(String text, String atAccount) {
        return sendGroupText(settings.getImGroupGameId(), settings.getImBotUserId(), text, atAccount) != null;
    }

    public boolean sendAdminStatsGroupText(String text) {
        return sendGroupText(settings.getImGroupAdminStatsId(), settings.getImBotUserId(), text, null) != null;
    }

    /** 已上传 OSS 的图片描述（URL + IM TIMImageElem 所需元数据）。 */
    public record PublishedImage(String url, String uuid, int size, int width, int height) {}

    /** 发群图片：成功返回 MsgSeq，失败或跳过返回 null。可用于重新截止时撤回。 */
    public Long sendImageToGroup(String groupId, PublishedImage image) {
        return sendGroupImage(groupId, settings.getImBotUserId(), image);
    }

    public String getGameGroupId() {
        return settings.getImGroupGameId();
    }

    public String getAdminStatsGroupId() {
        return settings.getImGroupAdminStatsId();
    }

    /** IM portrait_get 拉昵称（未配置返回 null）。 */
    public String getPortraitNickname(String imUserId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("To_Account", List.of(imUserId));
        body.put("TagList", List.of("Tag_Profile_IM_Nick"));
        Map<String, Object> payload = postImApi("profile/portrait_get", body);
        if (payload == null) {
            return null;
        }
        Object userProfiles = payload.get("UserProfileItem");
        if (!(userProfiles instanceof List<?> list)) {
            return null;
        }
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> item)) continue;
            if (!imUserId.equals(item.get("To_Account"))) continue;
            Object profileItems = item.get("ProfileItem");
            if (!(profileItems instanceof List<?> profiles)) continue;
            for (Object p : profiles) {
                if (p instanceof Map<?, ?> tag && "Tag_Profile_IM_Nick".equals(tag.get("Tag"))) {
                    Object rawValue = tag.get("Value");
                    String value = rawValue == null ? "" : String.valueOf(rawValue).trim();
                    if (!value.isEmpty()) {
                        return value;
                    }
                }
            }
        }
        return null;
    }

    // ===== 下注反馈 =====

    public void notifyBetSuccess(SangongUser user, String displayName, int door, long amount,
                                 long doorTotal, long balance) {
        notifyUserMention(user.getImUserId(), displayName,
            "下注成功 门" + door + "+" + amount + " 本门" + doorTotal + " 余" + balance);
    }

    public void notifyMultiBetSuccess(SangongUser user, String displayName, List<Integer> doors,
                                      long amountPerDoor, long totalAmount, long balance,
                                      boolean allIdle, String allIdleKeyword) {
        String keyword = allIdle ? ("公".equals(allIdleKeyword) ? "公" : "全") : "";
        String label = formatDoorsLabel(doors, allIdle, keyword);
        String text;
        if (allIdle) {
            text = "下注成功 " + keyword + "各" + amountPerDoor + " 共" + totalAmount + " 余" + balance;
        } else if (doors.size() == 1) {
            text = "下注成功 门" + label + "+" + amountPerDoor + " 余" + balance;
        } else {
            text = "下注成功 " + label + "门各" + amountPerDoor + " 共" + totalAmount + " 余" + balance;
        }
        notifyUserMention(user.getImUserId(), displayName, text);
    }

    public void notifyInsufficientBalance(SangongUser user, String displayName, int door,
                                          long amount, long balance) {
        notifyUserMention(user.getImUserId(), displayName, INSUFFICIENT_BALANCE_NOTICE);
    }

    public void notifyBetRejected(SangongUser user, String displayName, String reason) {
        notifyUserMention(user.getImUserId(), displayName, rejectText(reason));
    }

    public void notifyBetRecalled(SangongUser user, String displayName, int door, long amount, long balance) {
        notifyUserMention(user.getImUserId(), displayName, "撤回成功,本次下注无效");
    }

    public void notifyAdminLedgerAdjust(String action, String nickname, long amount, long balance,
                                        String operator, String note, long ledgerId) {
        String verb = "credit".equals(action) ? "上分" : "下分";
        StringBuilder sb = new StringBuilder();
        sb.append(verb).append(" 【").append(nickname).append("】").append(amount).append(" 余").append(balance);
        if (operator != null && !operator.isBlank()) {
            sb.append(" 操作:").append(operator);
        }
        String groupId = settings.getImGroupLedgerId();
        if (groupId.isEmpty()) {
            groupId = settings.getImGroupAdminStatsId();
        }
        sendGroupText(groupId, settings.getImBotUserId(), sb.toString(), null);
    }

    // ===== 流程通知 =====

    public void notifySubmitComplete(int periodNo, int placedCount, int failedCount) {
        sendGameGroupText("第" + periodNo + "期 提交完成 成功" + placedCount + "笔 失败" + failedCount + "笔");
    }

    /** 截止后按门汇总清单（文字），并记录 msg_seq 供重新截止撤回。 */
    public boolean notifyBettingSummary(SangongRound round, int periodNo, Map<String, Object> report) {
        int doorCount = intVal(report.get("doorCount"), 6);
        @SuppressWarnings("unchecked")
        Map<Integer, Long> doorTotals = (Map<Integer, Long>) report.getOrDefault("doorTotals", Map.of());
        long grandTotal = longVal(report.get("grandTotal"), 0);
        Object bankerDoor = report.get("bankerDoor");
        String packageLabel = bankerDoor != null ? String.valueOf(bankerDoor) : "-";
        String bankerLabel = String.valueOf(report.getOrDefault("bankerNickname", "")).trim();
        if (bankerLabel.isEmpty() || "null".equals(bankerLabel)) {
            bankerLabel = bankerDoor != null ? String.valueOf(bankerDoor) : "-";
        }

        List<String> lines = new ArrayList<>();
        lines.add("庄【" + bankerLabel + "】" + packageLabel + "包共" + grandTotal + "注");
        lines.add("==================");
        for (int door = 1; door <= doorCount; door++) {
            long amount = doorTotals.getOrDefault(door, 0L);
            String label = door >= 1 && door <= CIRCLED.length ? CIRCLED[door - 1] : String.valueOf(door);
            lines.add("第" + label + "门:" + amount + "注");
        }
        lines.add("==================");
        lines.add("请核对统计清单、出入认表");

        Long textSeq = sendGroupText(settings.getImGroupGameId(), settings.getImBotUserId(),
            String.join("\n", lines), null);

        Long imageSeq = null;
        try {
            Map<String, Object> sent = reportImages.generateAndSendBetReport(report, settings.getImGroupGameId());
            if (Boolean.TRUE.equals(sent.get("ok")) && sent.get("msgSeq") instanceof Number n) {
                imageSeq = n.longValue();
            } else if (sent.get("code") != null) {
                log.warn("bet cut-off image failed code={}", sent.get("code"));
            }
        } catch (Exception e) {
            log.warn("bet cut-off image: {}", e.getMessage());
        }

        round.setBetSummaryTextMsgSeq(textSeq);
        round.setBetSummaryImageMsgSeq(imageSeq);
        rounds.save(round);
        return textSeq != null;
    }

    /** 重新截止：撤回上次统计文字/图片。 */
    public Map<String, Object> recallBetSummaryMessages(SangongRound round) {
        List<Long> seqs = new ArrayList<>();
        if (round.getBetSummaryTextMsgSeq() != null && round.getBetSummaryTextMsgSeq() > 0) {
            seqs.add(round.getBetSummaryTextMsgSeq());
        }
        if (round.getBetSummaryImageMsgSeq() != null && round.getBetSummaryImageMsgSeq() > 0) {
            seqs.add(round.getBetSummaryImageMsgSeq());
        }
        if (seqs.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("skipped", true);
            out.put("reason", "no_stored_msg_seq");
            out.put("recalled", List.of());
            return out;
        }
        round.setBetSummaryTextMsgSeq(null);
        round.setBetSummaryImageMsgSeq(null);
        rounds.save(round);

        Map<String, Object> result = integration.recallGroupMessages(settings.getImGroupGameId(), seqs, "重新截止");
        result.put("requestedSeqs", seqs);
        result.put("fireAndForget", true);
        return result;
    }

    public boolean notifyDrawRecorded(int periodNo, int door, String amount, String handLabel) {
        return sendGameGroupText("第" + periodNo + "期 第" + door + "门开彩" + amount + " " + handLabel);
    }

    public void notifyNewRound(int periodNo) {
        sendGameGroupText("第" + periodNo + "期 新局开始 请先定庄");
    }

    public void notifyCoBankClosed(int periodNo) {
        sendGameGroupText("第" + periodNo + "期 合庄已截止 仍可下注");
    }

    public void notifyRoundRestart(int periodNo) {
        sendGameGroupText("第" + periodNo + "期 本局推倒重开");
    }

    public void notifyRoundSettled(int periodNo) {
        sendGameGroupText("第" + periodNo + "期 本局结算完成");
    }

    public void notifySettlementVoided(int periodNo) {
        sendGameGroupText("第" + periodNo + "期 结算已冲正，可重新开奖后结算");
    }

    /** 结算完成：发送结算明细图 + 积分表。 */
    public void notifySettlementSummary(int periodNo, Map<String, Object> report) {
        try {
            Map<String, Object> sent = reportImages.generateAndSendSettleReport(report, getGameGroupId());
            if (!Boolean.TRUE.equals(sent.get("ok"))) {
                log.warn("settle report image failed period={} code={}", periodNo, sent.get("code"));
            }
        } catch (Exception e) {
            log.warn("settle report image: {}", e.getMessage());
        }
        notifyGameGroupUserPointsImage();
    }

    public void notifyGameGroupUserPointsImage() {
        try {
            Map<String, Object> sent = reportImages.generateAndSendPointsImage(null, getGameGroupId());
            if (!Boolean.TRUE.equals(sent.get("ok")) && !"NO_USERS".equals(sent.get("code"))) {
                log.warn("user points image failed code={}", sent.get("code"));
            }
        } catch (Exception e) {
            log.warn("user points image: {}", e.getMessage());
        }
    }

    public void notifyAdminSettleBill(SangongRound round) {
        if (getAdminStatsGroupId() == null || getAdminStatsGroupId().isBlank()) {
            log.warn("stats settle bill: admin stats group not configured");
            return;
        }
        try {
            Map<String, Object> bill = settleBill.build(round);
            Map<String, Object> sent = reportImages.generateAndSendSettleBill(bill, getAdminStatsGroupId());
            if (!Boolean.TRUE.equals(sent.get("ok"))) {
                log.warn("stats settle bill image failed code={}", sent.get("code"));
            }
        } catch (Exception e) {
            log.warn("stats settle bill image: {}", e.getMessage());
        }
    }

    public void notifyTrendChart(long sessionId) {
        try {
            Map<String, Object> report = trendChart.buildForSession(sessionId);
            Map<String, Object> sent = reportImages.generateAndSendTrend(report, getGameGroupId());
            if (!Boolean.TRUE.equals(sent.get("ok"))) {
                log.warn("trend chart image failed code={}", sent.get("code"));
            }
        } catch (Exception e) {
            log.warn("trend chart image: {}", e.getMessage());
        }
    }

    /** 定庄 + 庄门群通知。 */
    public boolean notifyBankerSetup(String nickname, int door, Long limit) {
        String line = "庄【" + nickname + "】" + door + "门";
        if (limit != null && limit > 0) {
            line += " 限额" + limit;
        }
        return sendGameGroupText(line + "\n⬇️⬇️⬇️⬇️⬇️⬇️⬇️");
    }

    /** 合庄占股群通知：先撤回旧汇总，再发送新汇总并记录 msg_seq。 */
    public boolean notifyCoBankSummary(SangongRound round, int periodNo, Map<String, Object> summary) {
        recallCoBankSummaryMessage(round);

        List<String> lines = new ArrayList<>();
        lines.add("合庄庄家:");
        Object membersObj = summary.get("members");
        if (!(membersObj instanceof List<?> members) || members.isEmpty()) {
            lines.add("（暂无合庄）");
        } else {
            for (Object o : members) {
                if (!(o instanceof Map<?, ?> member)) continue;
                Object rawNick = member.get("nickname");
                String nickname = rawNick == null ? "" : String.valueOf(rawNick);
                double share = member.get("sharePercent") instanceof Number n ? n.doubleValue() : 0;
                lines.add("【" + nickname + "】" + String.format("%.2f", share) + "%");
            }
        }
        long pool = longVal(summary.get("poolTotal"), 0);
        lines.add("庄池：" + pool);

        Long textSeq = sendGroupText(settings.getImGroupGameId(), settings.getImBotUserId(),
            String.join("\n", lines), null);
        round.setCoBankSummaryMsgSeq(textSeq);
        rounds.save(round);
        return textSeq != null;
    }

    public Map<String, Object> recallCoBankSummaryMessage(SangongRound round) {
        Long seq = round.getCoBankSummaryMsgSeq();
        if (seq == null || seq <= 0) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("skipped", true);
            out.put("reason", "no_stored_msg_seq");
            out.put("recalled", List.of());
            return out;
        }
        round.setCoBankSummaryMsgSeq(null);
        rounds.save(round);
        Map<String, Object> result = integration.recallGroupMessages(settings.getImGroupGameId(),
            List.of(seq), "重新合庄");
        result.put("requestedSeqs", List.of(seq));
        result.put("fireAndForget", true);
        return result;
    }

    // ===== 内部 =====

    private void notifyUserMention(String imUserId, String displayName, String suffix) {
        sendGameGroupText("@" + displayName + " " + suffix, imUserId);
    }

    private String rejectText(String reason) {
        if (reason == null) return "本次下注无效";
        if (reason.equals("余额不足")) return INSUFFICIENT_BALANCE_NOTICE;
        if (reason.equals("庄门不可下注")) return "压到庄包";
        if (reason.equals("庄主不可在闲门下注") || reason.equals("庄主不可下注")) return "庄主不可下注";
        if (reason.equals("门号与金额须为正整数") || reason.equals("下注参数无效")) return "指令格式错误";
        if (reason.startsWith("门号须为")) return "门号无效";
        if (reason.startsWith("单注最小")) return "低于最小下注";
        if (reason.startsWith("单注最大")) return "超过最大下注";
        if (reason.equals("尚未定庄，当前不可下注")) return "未定庄,不可下注";
        if (reason.equals("尚未选定庄门，当前不可下注")) return "未选庄门,不可下注";
        if (reason.equals("本局已结算，不可下注")) return "本局已封盘";
        if (reason.equals("已录入开彩，不可下注")) return "本局已封盘";
        if (reason.equals("当前未开机或无可下注局") || reason.equals("当前不可下注")) return "当前不可下注";
        return "本次下注无效";
    }

    /** 发群图片（图片已上传 OSS），成功返回 MsgSeq。 */
    @SuppressWarnings("unchecked")
    private Long sendGroupImage(String groupId, String fromAccount, PublishedImage image) {
        long sdkAppId = props.getIm().getSdkAppId();
        String key = props.getIm().getKey();
        String admin = props.getIm().getRestAdmin();
        if (groupId == null || groupId.isEmpty() || fromAccount == null || fromAccount.isEmpty()) {
            log.warn("IM image skip: group or bot not configured");
            return null;
        }
        if (sdkAppId <= 0 || key == null || key.isEmpty() || admin == null || admin.isEmpty()) {
            log.warn("IM image skip: sdk/key/admin not configured");
            return null;
        }
        if (image == null || image.url() == null || image.url().isBlank()
            || image.uuid() == null || image.uuid().isBlank()) {
            log.warn("IM image skip: published image missing URL/UUID");
            return null;
        }
        try {
            int rand = random.nextInt(Integer.MAX_VALUE);
            String url = buildImUrl("group_open_http_svc/send_group_msg", sdkAppId, key, admin, rand);
            Map<String, Object> imageInfo = new LinkedHashMap<>();
            imageInfo.put("Type", 1);
            imageInfo.put("Size", image.size());
            imageInfo.put("Width", image.width());
            imageInfo.put("Height", image.height());
            imageInfo.put("URL", image.url());
            Map<String, Object> msgContent = new LinkedHashMap<>();
            msgContent.put("UUID", image.uuid());
            msgContent.put("ImageFormat", 1); // JPG
            msgContent.put("ImageInfoArray", List.of(imageInfo));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("GroupId", groupId);
            body.put("From_Account", fromAccount);
            body.put("Random", rand);
            body.put("MsgBody", List.of(Map.of(
                "MsgType", "TIMImageElem",
                "MsgContent", msgContent
            )));
            Request request = new Request.Builder().url(url)
                .post(RequestBody.create(mapper.writeValueAsString(body), JSON)).build();
            try (Response response = http.newCall(request).execute()) {
                String raw = response.body() != null ? response.body().string() : "";
                Map<String, Object> payload = raw.isBlank() ? null : mapper.readValue(raw, Map.class);
                if (payload == null) return null;
                int code = intVal(payload.get("ErrorCode"), -1);
                if (code != 0) {
                    log.warn("IM send_group image failed code={} info={}", code, payload.get("ErrorInfo"));
                    return null;
                }
                long msgSeq = longVal(payload.get("MsgSeq"), 0);
                return msgSeq > 0 ? msgSeq : null;
            }
        } catch (Exception e) {
            log.warn("IM send_group image exception: {}", e.getMessage());
            return null;
        }
    }

    public static String md5Hex(byte[] bytes) {
        try {
            byte[] dig = MessageDigest.getInstance("MD5").digest(bytes);
            return HexFormat.of().formatHex(dig);
        } catch (Exception e) {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    /** 发群文字，成功返回 MsgSeq。 */
    @SuppressWarnings("unchecked")
    private Long sendGroupText(String groupId, String fromAccount, String text, String atAccount) {
        long sdkAppId = props.getIm().getSdkAppId();
        String key = props.getIm().getKey();
        String admin = props.getIm().getRestAdmin();
        if (groupId == null || groupId.isEmpty() || fromAccount == null || fromAccount.isEmpty()) {
            log.warn("IM skip: group or bot not configured");
            return null;
        }
        if (sdkAppId <= 0 || key == null || key.isEmpty() || admin == null || admin.isEmpty()) {
            log.warn("IM skip: sdk/key/admin not configured");
            return null;
        }
        try {
            int rand = random.nextInt(Integer.MAX_VALUE);
            String url = buildImUrl("group_open_http_svc/send_group_msg", sdkAppId, key, admin, rand);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("GroupId", groupId);
            body.put("From_Account", fromAccount);
            body.put("Random", rand);
            body.put("MsgBody", List.of(Map.of(
                "MsgType", "TIMTextElem",
                "MsgContent", Map.of("Text", text)
            )));
            if (atAccount != null && !atAccount.isEmpty()) {
                body.put("GroupAtInfo", List.of(Map.of(
                    "GroupAtAllFlag", 0,
                    "GroupAt_Account", atAccount
                )));
            }
            Request request = new Request.Builder().url(url)
                .post(RequestBody.create(mapper.writeValueAsString(body), JSON)).build();
            try (Response response = http.newCall(request).execute()) {
                String raw = response.body() != null ? response.body().string() : "";
                Map<String, Object> payload = raw.isBlank() ? null : mapper.readValue(raw, Map.class);
                if (payload == null) {
                    return null;
                }
                int code = intVal(payload.get("ErrorCode"), -1);
                if (code != 0) {
                    log.warn("IM send_group_msg failed code={} info={}", code, payload.get("ErrorInfo"));
                    return null;
                }
                long msgSeq = longVal(payload.get("MsgSeq"), 0);
                return msgSeq > 0 ? msgSeq : null;
            }
        } catch (Exception e) {
            log.warn("IM send_group_msg exception: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postImApi(String path, Map<String, Object> body) {
        long sdkAppId = props.getIm().getSdkAppId();
        String key = props.getIm().getKey();
        String admin = props.getIm().getRestAdmin();
        if (sdkAppId <= 0 || key == null || key.isEmpty() || admin == null || admin.isEmpty()) {
            return null;
        }
        try {
            int rand = random.nextInt(Integer.MAX_VALUE);
            String url = buildImUrl(path, sdkAppId, key, admin, rand);
            Request request = new Request.Builder().url(url)
                .post(RequestBody.create(mapper.writeValueAsString(body), JSON)).build();
            try (Response response = http.newCall(request).execute()) {
                String raw = response.body() != null ? response.body().string() : "";
                return raw.isBlank() ? null : mapper.readValue(raw, Map.class);
            }
        } catch (Exception e) {
            log.warn("IM {} exception: {}", path, e.getMessage());
            return null;
        }
    }

    private String buildImUrl(String path, long sdkAppId, String key, String admin, int rand) {
        TLSSigAPIv2 api = new TLSSigAPIv2(sdkAppId, key);
        String adminSig = api.genUserSig(admin, 86400);
        String base = props.getIm().getRestBaseUrl().replaceAll("/+$", "");
        return base + "/" + path
            + "?sdkappid=" + sdkAppId
            + "&identifier=" + URLEncoder.encode(admin, StandardCharsets.UTF_8)
            + "&usersig=" + URLEncoder.encode(adminSig, StandardCharsets.UTF_8)
            + "&random=" + rand
            + "&contenttype=json";
    }

    private String formatDoorsLabel(List<Integer> doors, boolean allIdle, String keyword) {
        if (allIdle) {
            return "公".equals(keyword) ? "公" : "全";
        }
        if (doors == null || doors.isEmpty()) {
            return "";
        }
        if (doors.size() == 1 && doors.get(0) == 10) {
            return "10";
        }
        StringBuilder sb = new StringBuilder();
        for (int d : doors) sb.append(d);
        return sb.toString();
    }

    private static int intVal(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }

    private static long longVal(Object o, long def) {
        return o instanceof Number n ? n.longValue() : def;
    }
}
