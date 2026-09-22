package com.chat99.sangong.service;

import com.chat99.sangong.config.SangongProperties;
import com.chat99.sangong.domain.SangongUser;
import com.chat99.sangong.image.AvatarCache;
import com.chat99.sangong.image.EmojiAssets;
import com.chat99.sangong.image.ImageBorder;
import com.chat99.sangong.image.ImageText;
import com.chat99.sangong.image.RenderKit;
import com.chat99.sangong.image.ReportFonts;
import com.chat99.sangong.repository.UserRepository;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.font.GlyphVector;
import java.awt.font.FontRenderContext;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * 五类报表生图：积分表 / 走势图 / 统计清单 / 结算明细 / 流水账单。
 * 版式与 PHP（UserPointsImageService / BetReportImageService / SettleReportImageService /
 * TrendChartImageService / AdminSettleBillImageService）逐行对齐：同字体、同列宽、同配色。
 * 内存生成 JPG 直传 OSS（经主服务 Integration API），经 IM TIMImageElem 发送；不落本地磁盘。
 */
@Service
public class ReportImageService {
    private static final Logger log = LoggerFactory.getLogger(ReportImageService.class);

    private final SangongProperties props;
    private final UserRepository users;
    private final UserService userService;
    private final ImService im;
    private final OssImagePublisher oss;
    private final MainUserProfileService profiles;
    private final AvatarCache avatars;

    public ReportImageService(SangongProperties props, UserRepository users, UserService userService,
                              @Lazy ImService im, OssImagePublisher oss,
                              MainUserProfileService profiles, AvatarCache avatars) {
        this.props = props;
        this.users = users;
        this.userService = userService;
        this.im = im;
        this.oss = oss;
        this.profiles = profiles;
        this.avatars = avatars;
        ImageText.configureEmojiAssets(new EmojiAssets(Path.of(props.getStorageDir(), "emoji-cache")));
    }

    // ==================== 积分表（PHP UserPointsImageService） ====================

    public Map<String, Object> generateAndSendPointsImage(Long groupId, String imGroupId) {
        List<Map<String, Object>> rows = buildPointsRows(groupId);
        if (rows.isEmpty()) {
            return err("NO_USERS", "暂无非零积分玩家");
        }
        sortUsersForPointsTable(rows);

        RenderKit kit = kit();
        Map<String, String> faceUrls = fetchFaceUrls(rows, "imUserId");

        // 记分表模板三列 = 125 / 509 / 257 px（scale=3）
        int colNo = kit.px(42);
        int colName = kit.px(170);
        int colBalance = kit.px(86);
        int rowHeight = kit.px(21);
        int headerHeight = kit.px(21);
        int titleHeight = kit.px(21);
        int tableWidth = colNo + colName + colBalance;
        int height = Math.max(titleHeight + headerHeight + (rows.size() * rowHeight) + rowHeight, kit.px(120));
        List<Integer> cols = List.of(0, colNo, colNo + colName, tableWidth);
        int fontSm = kit.px(8);
        int fontMd = kit.px(9);
        int fontTitle = kit.titleFontSize(titleHeight);

        BufferedImage img = RenderKit.newCanvas(tableWidth, height);
        Graphics2D g = RenderKit.graphics(img);
        try {
            g.setColor(RenderKit.WHITE);
            g.fillRect(0, 0, tableWidth, height);

            int y = kit.drawOrangeBar(g, ReportFonts.title(), title() + " 积分表",
                tableWidth, 0, titleHeight, fontTitle);

            // 表头
            g.setColor(RenderKit.ORANGE);
            g.fillRect(0, y, tableWidth, headerHeight);
            g.setColor(RenderKit.GREEN);
            g.fillRect(cols.get(2), y, cols.get(3) - cols.get(2), headerHeight);
            ImageBorder.drawRowGrid(g, kit.scale(), 0, y, cols, headerHeight, RenderKit.GRAY_BORDER);
            String[] headers = {"编号", "玩家姓名", "剩余积分"};
            for (int i = 0; i < headers.length; i++) {
                ImageText.drawCenteredText(g, ReportFonts.regular(), headers[i], fontMd,
                    cols.get(i), y, cols.get(i + 1) - cols.get(i), headerHeight, RenderKit.WHITE);
            }
            y += headerHeight;

            // 数据行
            int rowIndex = 0;
            long totalBalance = 0;
            for (Map<String, Object> row : rows) {
                rowIndex++;
                long balance = lng(row.get("balance"));
                totalBalance += balance;
                Color bg = rowIndex % 2 == 1 ? RenderKit.GRAY_ROW : RenderKit.WHITE;
                g.setColor(bg);
                g.fillRect(0, y, tableWidth, rowHeight);
                ImageBorder.drawRowGrid(g, kit.scale(), 0, y, cols, rowHeight, RenderKit.GRAY_BORDER);

                ImageText.drawCenteredText(g, ReportFonts.regular(), String.valueOf(rowIndex), fontSm,
                    cols.get(0), y, cols.get(1) - cols.get(0), rowHeight, RenderKit.BLACK);

                kit.drawAvatarLeftCenteredName(g, ReportFonts.regular(), cols.get(1), y,
                    cols.get(2) - cols.get(1), rowHeight,
                    str(row.get("nickname")), faceUrls.get(str(row.get("imUserId"))), fontSm, RenderKit.BLACK);

                Color balanceColor = balance < 0 ? RenderKit.NEGATIVE
                    : balance > 0 ? RenderKit.POSITIVE : RenderKit.BLACK;
                kit.drawCenteredTextScoreAware(g, ReportFonts.regular(), String.valueOf(balance), fontSm,
                    cols.get(2), y, cols.get(3) - cols.get(2), rowHeight, balanceColor);
                y += rowHeight;
            }

            // 合计行
            g.setColor(RenderKit.ORANGE);
            g.fillRect(0, y, cols.get(2), rowHeight);
            g.setColor(RenderKit.GREEN);
            g.fillRect(cols.get(2), y, tableWidth - cols.get(2), rowHeight);
            int lw = ImageBorder.widthForRowHeight(kit.scale(), rowHeight);
            ImageBorder.rectangle(g, 0, y, tableWidth, y + rowHeight, RenderKit.GRAY_BORDER, lw);
            ImageBorder.line(g, cols.get(2), y, cols.get(2), y + rowHeight, RenderKit.GRAY_BORDER, lw);
            ImageText.drawCenteredText(g, ReportFonts.regular(), "玩家积分总和", fontMd,
                cols.get(0), y, cols.get(2) - cols.get(0), rowHeight, RenderKit.WHITE);
            ImageText.drawCenteredText(g, ReportFonts.regular(), String.valueOf(totalBalance), fontMd,
                cols.get(2), y, cols.get(3) - cols.get(2), rowHeight, RenderKit.WHITE);
        } finally {
            g.dispose();
        }

        Map<String, Object> result = publishAndSend(img, imGroupId, "points", false);
        if (Boolean.TRUE.equals(result.get("ok"))) {
            result.put("userCount", rows.size());
            result.put("imGroupId", result.getOrDefault("imGroupId", imGroupId));
        }
        return result;
    }

    private List<Map<String, Object>> buildPointsRows(Long groupId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SangongUser u : users.listAll()) {
            if (u.getBalance() == 0) continue;
            if (groupId != null) {
                if (groupId == 0L) {
                    if (u.getGroupId() != null) continue;
                } else if (!groupId.equals(u.getGroupId())) {
                    continue;
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("imUserId", u.getImUserId());
            row.put("nickname", userService.resolveNickname(u));
            row.put("balance", u.getBalance());
            rows.add(row);
        }
        return rows;
    }

    /** 积分表排序：按积分绝对值降序；同绝对值时数值大的在前；再按昵称。 */
    static void sortUsersForPointsTable(List<Map<String, Object>> rows) {
        rows.sort((a, b) -> {
            long balanceA = lng(a.get("balance"));
            long balanceB = lng(b.get("balance"));
            int absCmp = Long.compare(Math.abs(balanceB), Math.abs(balanceA));
            if (absCmp != 0) return absCmp;
            int balanceCmp = Long.compare(balanceB, balanceA);
            if (balanceCmp != 0) return balanceCmp;
            return str(a.get("nickname")).compareTo(str(b.get("nickname")));
        });
    }

    // ==================== 统计清单（PHP BetReportImageService） ====================

    @SuppressWarnings("unchecked")
    public Map<String, Object> generateAndSendBetReport(Map<String, Object> report, String imGroupId) {
        RenderKit kit = kit();
        int doorCount = Math.max(1, intVal(report.get("doorCount"), 6));
        List<Map<String, Object>> userRows =
            new ArrayList<>((List<Map<String, Object>>) report.getOrDefault("users", List.of()));
        userRows.sort((a, b) -> {
            int cmp = Long.compare(lng(b.get("total")), lng(a.get("total")));
            if (cmp != 0) return cmp;
            return str(a.get("nickname")).compareTo(str(b.get("nickname")));
        });
        String titleSuffix = str(report.get("titleSuffix")).trim();

        Set<String> imUserIds = new LinkedHashSet<>();
        String bankerImUserId = str(report.get("bankerImUserId")).trim();
        if (!bankerImUserId.isEmpty()) imUserIds.add(bankerImUserId);
        for (Map<String, Object> u : userRows) {
            String id = str(u.get("imUserId")).trim();
            if (!id.isEmpty()) imUserIds.add(id);
        }
        Map<String, String> faceUrls = profiles.getAvatarUrls(new ArrayList<>(imUserIds));
        avatars.prefetch(new ArrayList<>(faceUrls.values()));

        // 统计模板：编号 / 姓名 / 六门 / 总注数 = 125 / 317 / 189×6 / 192 px（scale=3）
        int colNo = kit.px(42);
        int colName = kit.px(106);
        int colDoor = kit.px(63);
        int colTotal = kit.px(64);
        int rowHeight = kit.px(21);
        int headerHeight = kit.px(21);
        int titleHeight = kit.px(21);
        int bankerRowHeight = kit.px(21);
        int sectionGap = rowHeight;
        int tableWidth = colNo + colName + (doorCount * colDoor) + colTotal;
        int height = Math.max(titleHeight + headerHeight + rowHeight + bankerRowHeight
            + sectionGap + headerHeight + (userRows.size() * rowHeight), kit.px(200));
        int fontSm = kit.px(8);
        int fontMd = kit.px(9);
        int fontTitle = kit.titleFontSize(titleHeight);

        List<Integer> cols = new ArrayList<>();
        cols.add(0);
        cols.add(colNo);
        cols.add(colNo + colName);
        for (int i = 1; i <= doorCount; i++) {
            cols.add(colNo + colName + i * colDoor);
        }
        cols.add(tableWidth);
        int totalColIndex = cols.size() - 2;

        BufferedImage img = RenderKit.newCanvas(tableWidth, height);
        Graphics2D g = RenderKit.graphics(img);
        try {
            g.setColor(RenderKit.WHITE);
            g.fillRect(0, 0, tableWidth, height);

            int y = kit.drawOrangeBar(g, ReportFonts.title(), title() + " 统计清单" + titleSuffix,
                tableWidth, 0, titleHeight, fontTitle);

            // ---- 汇总区 ----
            Map<?, ?> doorTotals = report.get("doorTotals") instanceof Map<?, ?> m ? m : Map.of();
            long grandTotal = lng(report.get("grandTotal"));
            Object bankerDoor = report.get("bankerDoor");
            String bankerNickname = str(report.get("bankerNickname")).trim();
            if (bankerNickname.isEmpty()) bankerNickname = "庄家";

            List<String> headers = new ArrayList<>(List.of("编号", "庄家详情"));
            for (int door = 1; door <= doorCount; door++) headers.add(door + "门");
            headers.add("总注数");
            y = drawBetHeaderRow(g, kit, cols, y, headerHeight, headers, fontMd, totalColIndex);

            List<String> cells = new ArrayList<>(List.of("", "注数"));
            for (int door = 1; door <= doorCount; door++) {
                cells.add(String.valueOf(doorVal(doorTotals, door)));
            }
            cells.add(String.valueOf(grandTotal));
            y = drawBetDataRow(g, kit, cols, y, rowHeight, cells, true, fontSm, Map.of("注数", RenderKit.GREEN));

            String bankerText = "庄:" + (bankerDoor != null ? String.valueOf(intVal(bankerDoor, 0)) : "-")
                + "门, 共" + grandTotal + "注";
            y = drawBetBankerRow(g, kit, cols, y, bankerRowHeight, bankerNickname, bankerText,
                faceUrls.get(bankerImUserId), fontSm, fontMd, totalColIndex);

            y += sectionGap;

            // ---- 玩家区 ----
            headers = new ArrayList<>(List.of("编号", "玩家姓名"));
            for (int door = 1; door <= doorCount; door++) headers.add(door + "门");
            headers.add("总注数");
            y = drawBetHeaderRow(g, kit, cols, y, headerHeight, headers, fontMd, totalColIndex);

            int rowIndex = 0;
            for (Map<String, Object> u : userRows) {
                rowIndex++;
                Map<?, ?> doors = u.get("doors") instanceof Map<?, ?> m ? m : Map.of();
                List<String> rowCells = new ArrayList<>();
                rowCells.add(String.valueOf(rowIndex));
                rowCells.add("");
                for (int door = 1; door <= doorCount; door++) {
                    long amount = doorVal(doors, door);
                    rowCells.add(amount > 0 ? String.valueOf(amount) : "");
                }
                rowCells.add(String.valueOf(lng(u.get("total"))));

                boolean altRow = rowIndex % 2 == 1;
                Color bg = altRow ? RenderKit.GRAY_ROW : RenderKit.WHITE;
                g.setColor(bg);
                g.fillRect(0, y, tableWidth, rowHeight);
                ImageBorder.drawRowGrid(g, kit.scale(), 0, y, cols, rowHeight, RenderKit.GRAY_BORDER);

                ImageText.drawCenteredText(g, ReportFonts.regular(), rowCells.get(0), fontSm,
                    cols.get(0), y, cols.get(1) - cols.get(0), rowHeight, RenderKit.BLACK);
                kit.drawAvatarLeftCenteredName(g, ReportFonts.regular(), cols.get(1), y,
                    cols.get(2) - cols.get(1), rowHeight,
                    str(u.get("nickname")), faceUrls.get(str(u.get("imUserId")).trim()), fontSm, RenderKit.BLACK);
                for (int i = 2; i < rowCells.size(); i++) {
                    ImageText.drawCenteredText(g, ReportFonts.regular(), rowCells.get(i), fontSm,
                        cols.get(i), y, cols.get(i + 1) - cols.get(i), rowHeight, RenderKit.BLACK);
                }
                y += rowHeight;
            }
        } finally {
            g.dispose();
        }
        return publishAndSend(img, imGroupId, "bet", true);
    }

    private int drawBetHeaderRow(Graphics2D g, RenderKit kit, List<Integer> cols, int y, int h,
                                 List<String> headers, int fontMd, int totalColIndex) {
        int rowWidth = cols.get(cols.size() - 1);
        g.setColor(RenderKit.ORANGE);
        g.fillRect(0, y, rowWidth, h);
        // 表头「总注数」格背景色
        g.setColor(RenderKit.GREEN);
        g.fillRect(cols.get(totalColIndex), y, cols.get(totalColIndex + 1) - cols.get(totalColIndex), h);
        ImageBorder.drawRowGrid(g, kit.scale(), 0, y, cols, h, RenderKit.GRAY_BORDER);
        for (int i = 0; i < headers.size(); i++) {
            ImageText.drawCenteredText(g, ReportFonts.regular(), headers.get(i), fontMd,
                cols.get(i), y, cols.get(i + 1) - cols.get(i), h, RenderKit.WHITE);
        }
        return y + h;
    }

    private int drawBetDataRow(Graphics2D g, RenderKit kit, List<Integer> cols, int y, int h,
                               List<String> cells, boolean altRow, int fontSm, Map<String, Color> cellColors) {
        int rowWidth = cols.get(cols.size() - 1);
        g.setColor(altRow ? RenderKit.GRAY_ROW : RenderKit.WHITE);
        g.fillRect(0, y, rowWidth, h);
        for (int i = 0; i < cells.size(); i++) {
            Color cellBg = cellColors.get(cells.get(i));
            if (cellBg != null) {
                g.setColor(cellBg);
                g.fillRect(cols.get(i), y, cols.get(i + 1) - cols.get(i), h);
            }
        }
        ImageBorder.drawRowGrid(g, kit.scale(), 0, y, cols, h, RenderKit.GRAY_BORDER);
        for (int i = 0; i < cells.size(); i++) {
            ImageText.drawCenteredText(g, ReportFonts.regular(), cells.get(i), fontSm,
                cols.get(i), y, cols.get(i + 1) - cols.get(i), h, RenderKit.BLACK);
        }
        return y + h;
    }

    private int drawBetBankerRow(Graphics2D g, RenderKit kit, List<Integer> cols, int y, int h,
                                 String nickname, String bankerText, String faceUrl,
                                 int fontSm, int fontMd, int totalColIndex) {
        int rowWidth = cols.get(cols.size() - 1);
        g.setColor(RenderKit.WHITE);
        g.fillRect(0, y, rowWidth, h);
        g.setColor(RenderKit.GREEN);
        g.fillRect(cols.get(1), y, cols.get(2) - cols.get(1), h);

        // 庄家姓名：头像靠左、昵称基线居中（PHP drawAvatarAndName 变体）
        drawBetAvatarAndName(g, kit, cols.get(1), y, cols.get(2) - cols.get(1), h, nickname, faceUrl, fontSm);

        ImageText.drawCenteredText(g, ReportFonts.regular(), bankerText, fontMd,
            cols.get(2), y, cols.get(totalColIndex) - cols.get(2), h, RenderKit.BANKER_TEXT);

        // 庄家行：1～N 门合并为一格，不画门间竖线
        int lw = ImageBorder.widthForRowHeight(kit.scale(), h);
        ImageBorder.rectangle(g, 0, y, rowWidth, y + h, RenderKit.GRAY_BORDER, lw);
        ImageBorder.line(g, cols.get(1), y, cols.get(1), y + h, RenderKit.GRAY_BORDER, lw);
        ImageBorder.line(g, cols.get(2), y, cols.get(2), y + h, RenderKit.GRAY_BORDER, lw);
        ImageBorder.line(g, cols.get(totalColIndex), y, cols.get(totalColIndex), y + h, RenderKit.GRAY_BORDER, lw);
        return y + h;
    }

    private void drawBetAvatarAndName(Graphics2D g, RenderKit kit, int x, int y, int width, int height,
                                      String nickname, String faceUrl, int fontSize) {
        int avatarSize = height;
        BufferedImage avatar = kit.loadSquareAvatar(faceUrl, avatarSize, RenderKit.GRAY_AVATAR);
        if (avatar != null) {
            g.drawImage(avatar, x, y, avatarSize, avatarSize, null);
        } else {
            g.setColor(RenderKit.GRAY_AVATAR);
            g.fillRect(x, y, avatarSize, avatarSize);
        }
        int textX = x + avatarSize + kit.px(2);
        int textWidth = width - (textX - x) - kit.px(4);
        String label = ImageText.truncateToWidth(ReportFonts.regular(), nickname, fontSize, textWidth);
        Rectangle2D ink = inkBounds(ReportFonts.regular(), label.isEmpty() ? "国" : label, fontSize);
        int textH = (int) Math.round(ink.getHeight());
        int textY = y + (height + textH) / 2 - 2;
        ImageText.drawText(g, ReportFonts.regular(), fontSize, textX, textY, RenderKit.BLACK, label);
    }

    // ==================== 结算明细（PHP SettleReportImageService） ====================

    @SuppressWarnings("unchecked")
    public Map<String, Object> generateAndSendSettleReport(Map<String, Object> report, String imGroupId) {
        RenderKit kit = kit();
        int doorCount = Math.max(1, intVal(report.get("doorCount"), 6));

        List<Map<String, Object>> players =
            new ArrayList<>((List<Map<String, Object>>) report.getOrDefault("players", List.of()));
        players.sort((a, b) -> {
            int cmp = Long.compare(lng(b.get("net")), lng(a.get("net")));
            if (cmp != 0) return cmp;
            return str(a.get("nickname")).compareTo(str(b.get("nickname")));
        });

        List<Map<String, Object>> coBankers = resolveCoBankers(report);
        long mainBankerUserId = lng(report.get("bankerUserId"));
        coBankers.sort((a, b) -> {
            int aMain = lng(a.get("userId")) == mainBankerUserId ? 0 : 1;
            int bMain = lng(b.get("userId")) == mainBankerUserId ? 0 : 1;
            if (aMain != bMain) return Integer.compare(aMain, bMain);
            int cmp = Long.compare(lng(b.get("net")), lng(a.get("net")));
            if (cmp != 0) return cmp;
            return str(a.get("nickname")).compareTo(str(b.get("nickname")));
        });

        long periodNo = lng(report.get("periodNo"));

        Set<String> imUserIds = new LinkedHashSet<>();
        String bankerImUserId = str(report.get("bankerImUserId")).trim();
        if (!bankerImUserId.isEmpty()) imUserIds.add(bankerImUserId);
        for (Map<String, Object> banker : coBankers) {
            String id = str(banker.get("imUserId")).trim();
            if (!id.isEmpty()) imUserIds.add(id);
        }
        for (Map<String, Object> player : players) {
            String id = str(player.get("imUserId")).trim();
            if (!id.isEmpty()) imUserIds.add(id);
        }
        Map<String, String> faceUrls = profiles.getAvatarUrls(new ArrayList<>(imUserIds));
        avatars.prefetch(new ArrayList<>(faceUrls.values()));

        // 结算模板：左侧沿用统计表六门比例；右侧汇总区按参考图收窄
        int colNo = kit.px(42);
        int colName = kit.px(106);
        int colDoor = kit.px(63);
        int colGrand = kit.px(64);
        int colCommission = kit.px(50);
        int colNet = kit.px(60);
        int colBefore = kit.px(62);
        int colAfter = kit.px(70);
        int rowHeight = kit.px(21);
        int headerHeight = kit.px(21);
        int titleHeight = kit.px(21);
        int bankerRowHeight = kit.px(21);
        int bankerDataRows = 4;

        int tableWidth = colNo + colName + (doorCount * colDoor)
            + colGrand + colCommission + colNet + colBefore + colAfter;
        int coBankSectionHeight = coBankers.isEmpty() ? 0 : headerHeight + coBankers.size() * rowHeight;
        int height = Math.max(titleHeight
            + headerHeight + bankerDataRows * rowHeight + bankerRowHeight
            + coBankSectionHeight
            + rowHeight
            + headerHeight
            + players.size() * rowHeight, kit.px(240));
        int fontSm = kit.px(7);
        int fontMd = kit.px(8);
        int fontTitle = kit.titleFontSize(titleHeight);

        List<Integer> cols = new ArrayList<>();
        cols.add(0);
        cols.add(colNo);
        cols.add(colNo + colName);
        for (int i = 1; i <= doorCount; i++) {
            cols.add(colNo + colName + i * colDoor);
        }
        int tail = colNo + colName + doorCount * colDoor;
        cols.add(tail + colGrand);
        cols.add(tail + colGrand + colCommission);
        cols.add(tail + colGrand + colCommission + colNet);
        cols.add(tail + colGrand + colCommission + colNet + colBefore);
        cols.add(tableWidth);
        SettleCtx ctx = new SettleCtx(kit, cols, doorCount, rowHeight, headerHeight,
            bankerRowHeight, fontSm, fontMd);

        BufferedImage img = RenderKit.newCanvas(tableWidth, height);
        Graphics2D g = RenderKit.graphics(img);
        try {
            g.setColor(RenderKit.WHITE);
            g.fillRect(0, 0, tableWidth, height);

            int y = kit.drawOrangeBar(g, ReportFonts.title(), title() + "结算明细-" + periodNo,
                tableWidth, 0, titleHeight, fontTitle);

            y = drawSettleBankerSection(g, ctx, y, report, faceUrls);
            if (!coBankers.isEmpty()) {
                y += rowHeight;
                y = drawSettleCoBankSection(g, ctx, y, coBankers, faceUrls);
            } else {
                y += rowHeight;
            }
            drawSettlePlayerSection(g, ctx, y, players, faceUrls);
        } finally {
            g.dispose();
        }
        return publishAndSend(img, imGroupId, "settle", true);
    }

    private record SettleCtx(RenderKit kit, List<Integer> cols, int doorCount, int rowHeight,
                             int headerHeight, int bankerRowHeight, int fontSm, int fontMd) {
        int tableWidth() { return cols.get(cols.size() - 1); }
        int doorsEnd() { return 2 + doorCount; }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> resolveCoBankers(Map<String, Object> report) {
        if (intVal(report.get("coBankCount"), 0) <= 0) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : (List<?>) report.getOrDefault("bankers", List.of())) {
            if (o instanceof Map<?, ?> banker && lng(((Map<String, Object>) banker).get("userId")) > 0) {
                out.add((Map<String, Object>) banker);
            }
        }
        return out;
    }

    private int drawSettleBankerSection(Graphics2D g, SettleCtx ctx, int y,
                                        Map<String, Object> report, Map<String, String> faceUrls) {
        int doorCount = ctx.doorCount();
        Map<?, ?> doorTotals = report.get("doorTotals") instanceof Map<?, ?> m ? m : Map.of();
        long grandTotal = lng(report.get("grandTotal"));
        Map<?, ?> doorMultipliers = report.get("doorMultipliers") instanceof Map<?, ?> m ? m : Map.of();
        Map<?, ?> doorAmounts = report.get("doorAmounts") instanceof Map<?, ?> m ? m : Map.of();
        Map<?, ?> doorPoints = report.get("doorPoints") instanceof Map<?, ?> m ? m : Map.of();

        List<String> headers = new ArrayList<>(List.of("编号", "庄家详情"));
        for (int door = 1; door <= doorCount; door++) headers.add(door + "门");
        headers.addAll(List.of("总注数", "佣金", "合计", "上局积分", "剩余积分"));
        y = drawSettleHeaderRow(g, ctx, y, headers, true);

        int rakePercent = intVal(report.get("rakePercent"), 0);
        long rake = lng(report.get("rake"));

        List<String> betCells = new ArrayList<>(List.of("", "注数"));
        for (int door = 1; door <= doorCount; door++) {
            betCells.add(String.valueOf(doorVal(doorTotals, door)));
        }
        betCells.addAll(List.of(String.valueOf(grandTotal), rakePercent + "%", String.valueOf(rake), "", ""));
        y = drawSettleLabelDataRow(g, ctx, y, betCells, true, false);

        List<String> multCells = new ArrayList<>(List.of("", "倍数"));
        for (int door = 1; door <= doorCount; door++) {
            multCells.add(doorStr(doorMultipliers, door));
        }
        multCells.addAll(List.of("", "", "", "", ""));
        y = drawSettleLabelDataRow(g, ctx, y, multCells, false, true);

        List<String> amountCells = new ArrayList<>(List.of("", "金额"));
        long amountTotal = 0;
        for (int door = 1; door <= doorCount; door++) {
            String doorAmount = doorStr(doorAmounts, door);
            amountCells.add(doorAmount);
            if (!doorAmount.isEmpty()) {
                amountTotal += parseLongSafe(doorAmount);
            }
        }
        String amountTotalText = amountTotal > 0 ? "+" + amountTotal : String.valueOf(amountTotal);
        amountCells.addAll(List.of("", "", amountTotalText, "", ""));
        y = drawSettleLabelDataRow(g, ctx, y, amountCells, true, true);

        List<String> pointCells = new ArrayList<>(List.of("", "红包点数"));
        for (int door = 1; door <= doorCount; door++) {
            pointCells.add(doorStr(doorPoints, door));
        }
        pointCells.addAll(List.of("", "", "", "", ""));
        y = drawSettleLabelDataRow(g, ctx, y, pointCells, false, false);

        int bankerDoor = intVal(report.get("bankerDoor"), 0);
        Object bankerHand = report.get("bankerHand") instanceof Map<?, ?> hand ? hand.get("amount") : null;
        long eat = lng(report.get("bankerEat"));
        long pay = lng(report.get("bankerPay"));
        long bankerNet = report.containsKey("mainBankerNet")
            ? lng(report.get("mainBankerNet")) : lng(report.get("bankerNet"));
        String bankerNickname = str(report.get("bankerNickname")).trim();
        if (bankerNickname.isEmpty()) bankerNickname = "庄家";
        String bankerImUserId = str(report.get("bankerImUserId")).trim();
        String netText = bankerNet >= 0 ? "+" + bankerNet : String.valueOf(bankerNet);
        String bankerText = "庄:" + bankerDoor + "门 点数:" + (bankerHand == null ? "-" : bankerHand)
            + " 吃:" + eat + " 赔:" + pay + " 出入:" + netText;

        return drawSettleBankerSummaryRow(g, ctx, y, bankerNickname, bankerText,
            faceUrls.get(bankerImUserId), netText,
            formatBalance(report.get("bankerBalanceBefore")), formatBalance(report.get("bankerBalanceAfter")));
    }

    private int drawSettleHeaderRow(Graphics2D g, SettleCtx ctx, int y, List<String> headers, boolean bankerTable) {
        List<Integer> cols = ctx.cols();
        int h = ctx.headerHeight();
        int rowWidth = ctx.tableWidth();

        g.setColor(RenderKit.ORANGE);
        g.fillRect(0, y, rowWidth, h);
        if (bankerTable) {
            g.setColor(RenderKit.GREEN);
            g.fillRect(cols.get(1), y, cols.get(2) - cols.get(1), h);
        }
        // 尾部「总注数/佣金/合计/上局积分/剩余积分」背景
        int tailStart = cols.size() - 6;
        g.setColor(RenderKit.GREEN);
        g.fillRect(cols.get(tailStart), y, cols.get(cols.size() - 1) - cols.get(tailStart), h);

        drawSettleGridBorders(g, ctx, y, h, !bankerTable);

        int mergeIndex = ctx.doorsEnd();
        int colCount = Math.min(headers.size(), cols.size() - 1);
        for (int i = 0; i < colCount; i++) {
            String label = headers.get(i);
            if (label.isEmpty()) continue;
            if (!bankerTable && i == mergeIndex) {
                ImageText.drawCenteredText(g, ReportFonts.regular(), label, ctx.fontMd(),
                    cols.get(mergeIndex), y, cols.get(mergeIndex + 2) - cols.get(mergeIndex), h, RenderKit.WHITE);
                continue;
            }
            ImageText.drawCenteredText(g, ReportFonts.regular(), label, ctx.fontMd(),
                cols.get(i), y, cols.get(i + 1) - cols.get(i), h, RenderKit.WHITE);
        }
        return y + h;
    }

    private int drawSettleLabelDataRow(Graphics2D g, SettleCtx ctx, int y, List<String> cells,
                                       boolean altRow, boolean signedDoorValues) {
        List<Integer> cols = ctx.cols();
        int h = ctx.rowHeight();
        int rowWidth = ctx.tableWidth();
        int doorCount = ctx.doorCount();

        g.setColor(altRow ? RenderKit.GRAY_ROW : RenderKit.WHITE);
        g.fillRect(0, y, rowWidth, h);
        g.setColor(RenderKit.GREEN);
        g.fillRect(cols.get(1), y, cols.get(2) - cols.get(1), h);
        drawSettleGridBorders(g, ctx, y, h, false);

        int colCount = Math.min(cells.size(), cols.size() - 1);
        for (int i = 0; i < colCount; i++) {
            String text = cells.get(i);
            Color color = RenderKit.BLACK;
            if (i == 1) {
                color = RenderKit.WHITE;
            } else if (signedDoorValues && !text.isEmpty()) {
                int totalColIndex = 2 + doorCount + 2;
                if ((i >= 2 && i < 2 + doorCount) || i == totalColIndex) {
                    color = RenderKit.signedColor(text);
                }
            }
            ImageText.drawCenteredText(g, RenderKit.fontForCell(ReportFonts.regular(), color), text,
                ctx.fontSm(), cols.get(i), y, cols.get(i + 1) - cols.get(i), h, color);
        }
        return y + h;
    }

    private int drawSettleBankerSummaryRow(Graphics2D g, SettleCtx ctx, int y, String nickname,
                                           String bankerText, String faceUrl, String netText,
                                           String balanceBefore, String balanceAfter) {
        List<Integer> cols = ctx.cols();
        int h = ctx.bankerRowHeight();
        int rowWidth = ctx.tableWidth();
        int doorsEnd = ctx.doorsEnd();

        g.setColor(RenderKit.WHITE);
        g.fillRect(0, y, rowWidth, h);
        g.setColor(RenderKit.GREEN);
        g.fillRect(cols.get(1), y, cols.get(2) - cols.get(1), h);

        drawSettleAvatarAndName(g, ctx.kit(), cols.get(1), y, cols.get(2) - cols.get(1), h,
            nickname, faceUrl, ctx.fontSm(), RenderKit.WHITE);

        ImageText.drawCenteredText(g, ReportFonts.regular(), bankerText, ctx.fontSm(),
            cols.get(2), y, cols.get(doorsEnd) - cols.get(2), h, RenderKit.BANKER_TEXT);

        int mergeStart = doorsEnd;
        int mergeEnd = doorsEnd + 2;
        g.setColor(RenderKit.GREEN);
        g.fillRect(cols.get(mergeStart), y, cols.get(mergeEnd) - cols.get(mergeStart), h);
        ImageText.drawCenteredText(g, ReportFonts.regular(), "庄家合并", ctx.fontSm(),
            cols.get(mergeStart), y, cols.get(mergeEnd) - cols.get(mergeStart), h, RenderKit.WHITE);

        String[] tailTexts = {netText, balanceBefore, balanceAfter};
        for (int t = 0; t < tailTexts.length; t++) {
            int index = doorsEnd + 2 + t;
            String text = tailTexts[t];
            if (text.isEmpty() || index + 1 >= cols.size()) continue;
            Color color = t == 0 ? RenderKit.signedColor(netText) : RenderKit.remainingBalanceColor(text);
            ImageText.drawCenteredText(g, RenderKit.fontForCell(ReportFonts.regular(), color), text,
                ctx.fontSm(), cols.get(index), y, cols.get(index + 1) - cols.get(index), h, color);
        }

        // 庄家行边框：门列合并 + 总注/佣金合并
        int lw = ImageBorder.widthForRowHeight(ctx.kit().scale(), h);
        ImageBorder.rectangle(g, 0, y, rowWidth, y + h, RenderKit.GRAY_BORDER, lw);
        ImageBorder.line(g, cols.get(1), y, cols.get(1), y + h, RenderKit.GRAY_BORDER, lw);
        ImageBorder.line(g, cols.get(2), y, cols.get(2), y + h, RenderKit.GRAY_BORDER, lw);
        ImageBorder.line(g, cols.get(doorsEnd), y, cols.get(doorsEnd), y + h, RenderKit.GRAY_BORDER, lw);
        for (int i = doorsEnd + 2; i < cols.size(); i++) {
            ImageBorder.line(g, cols.get(i), y, cols.get(i), y + h, RenderKit.GRAY_BORDER, lw);
        }
        return y + h;
    }

    private int drawSettleCoBankSection(Graphics2D g, SettleCtx ctx, int y,
                                        List<Map<String, Object>> coBankers, Map<String, String> faceUrls) {
        int doorCount = ctx.doorCount();
        List<Integer> cols = ctx.cols();
        int mergeIndex = ctx.doorsEnd();

        // 合庄表头
        int h = ctx.headerHeight();
        g.setColor(RenderKit.ORANGE);
        g.fillRect(0, y, cols.get(mergeIndex), h);
        g.setColor(RenderKit.GREEN);
        g.fillRect(cols.get(mergeIndex), y, cols.get(cols.size() - 1) - cols.get(mergeIndex), h);
        drawSettleCoBankGridBorders(g, ctx, y, h);

        List<String> headers = new ArrayList<>(List.of("编号", "庄家姓名"));
        for (int door = 1; door <= doorCount; door++) headers.add("");
        headers.addAll(List.of("庄家合计", "", "合计", "上局积分", "剩余积分"));
        int colCount = Math.min(headers.size(), cols.size() - 1);
        for (int i = 0; i < colCount; i++) {
            String label = headers.get(i);
            if (label.isEmpty()) continue;
            if (i == mergeIndex) {
                ImageText.drawCenteredText(g, ReportFonts.regular(), label, ctx.fontMd(),
                    cols.get(mergeIndex), y, cols.get(mergeIndex + 2) - cols.get(mergeIndex), h, RenderKit.WHITE);
                continue;
            }
            ImageText.drawCenteredText(g, ReportFonts.regular(), label, ctx.fontMd(),
                cols.get(i), y, cols.get(i + 1) - cols.get(i), h, RenderKit.WHITE);
        }
        y += h;

        int rowIndex = 0;
        for (Map<String, Object> banker : coBankers) {
            rowIndex++;
            y = drawSettleCoBankRow(g, ctx, y, banker, faceUrls, rowIndex, rowIndex % 2 == 1);
        }
        return y;
    }

    private int drawSettleCoBankRow(Graphics2D g, SettleCtx ctx, int y, Map<String, Object> banker,
                                    Map<String, String> faceUrls, int rowIndex, boolean altRow) {
        List<Integer> cols = ctx.cols();
        int h = ctx.rowHeight();
        int rowWidth = ctx.tableWidth();
        int doorsEnd = ctx.doorsEnd();
        int mergeIndex = doorsEnd;
        int tailNetIndex = mergeIndex + 2;
        Color bg = altRow ? RenderKit.GRAY_ROW : RenderKit.WHITE;

        String nickname = str(banker.get("nickname"));
        String imUserId = str(banker.get("imUserId")).trim();
        long net = lng(banker.get("net"));
        String netText = net >= 0 ? "+" + net : String.valueOf(net);
        String netPlain = String.valueOf(net);
        String sharePercent = formatSharePercent(dbl(banker.get("sharePercent")));
        long betShare = lng(banker.get("betShare"));
        long rakeShare = lng(banker.get("rakeShare"));
        String detailText = "占:" + sharePercent + "% 注:" + betShare + " 水:" + rakeShare + " 出入:" + netPlain;

        g.setColor(bg);
        g.fillRect(0, y, rowWidth, h);
        drawSettleCoBankGridBorders(g, ctx, y, h);

        g.setColor(bg);
        g.fillRect(cols.get(2), y, cols.get(doorsEnd) - 1 - cols.get(2), h);

        ImageText.drawCenteredText(g, ReportFonts.regular(), String.valueOf(rowIndex), ctx.fontSm(),
            cols.get(0), y, cols.get(1) - cols.get(0), h, RenderKit.BLACK);

        drawSettleAvatarAndName(g, ctx.kit(), cols.get(1), y, cols.get(2) - cols.get(1), h,
            nickname, faceUrls.get(imUserId), ctx.fontSm(), RenderKit.BLACK);

        ImageText.drawCenteredText(g, ReportFonts.regular(), detailText, ctx.fontSm(),
            cols.get(2), y, cols.get(doorsEnd) - cols.get(2), h, RenderKit.BANKER_TEXT);

        g.setColor(RenderKit.GREEN);
        g.fillRect(cols.get(mergeIndex), y, cols.get(mergeIndex + 2) - cols.get(mergeIndex), h);
        ctx.kit().drawAvatarLeftCenteredName(g, ReportFonts.regular(), cols.get(mergeIndex), y,
            cols.get(mergeIndex + 2) - cols.get(mergeIndex), h,
            nickname, faceUrls.get(imUserId), ctx.fontSm(), RenderKit.WHITE);

        String[] tail = {netText, formatBalance(banker.get("balanceBefore")), formatBalance(banker.get("balanceAfter"))};
        for (int t = 0; t < tail.length; t++) {
            int index = tailNetIndex + t;
            String text = tail[t];
            if (text.isEmpty() || index + 1 >= cols.size()) continue;
            Color color = t == 0 ? RenderKit.signedColor(netText) : RenderKit.remainingBalanceColor(text);
            ImageText.drawCenteredText(g, RenderKit.fontForCell(ReportFonts.regular(), color), text,
                ctx.fontSm(), cols.get(index), y, cols.get(index + 1) - cols.get(index), h, color);
        }
        return y + h;
    }

    /** 合庄表边框：中间宽列（各门合并）与「庄家合计」（总注+佣金合并）不画内部分隔线。 */
    private void drawSettleCoBankGridBorders(Graphics2D g, SettleCtx ctx, int y, int h) {
        List<Integer> cols = ctx.cols();
        int doorsEnd = ctx.doorsEnd();
        int rowWidth = ctx.tableWidth();
        int lw = ImageBorder.widthForRowHeight(ctx.kit().scale(), h);
        ImageBorder.rectangle(g, 0, y, rowWidth, y + h, RenderKit.GRAY_BORDER, lw);
        for (int idx = 0; idx < cols.size(); idx++) {
            if (idx == 0) continue;
            if (idx >= 3 && idx < doorsEnd) continue;
            if (idx == doorsEnd + 1) continue;
            ImageBorder.line(g, cols.get(idx), y, cols.get(idx), y + h, RenderKit.GRAY_BORDER, lw);
        }
    }

    private int drawSettlePlayerSection(Graphics2D g, SettleCtx ctx, int y,
                                        List<Map<String, Object>> players, Map<String, String> faceUrls) {
        int doorCount = ctx.doorCount();
        List<String> headers = new ArrayList<>(List.of("编号", "玩家姓名"));
        for (int door = 1; door <= doorCount; door++) headers.add(door + "门");
        headers.addAll(List.of("玩家合计", "", "合计", "上局积分", "剩余积分"));
        y = drawSettleHeaderRow(g, ctx, y, headers, false);

        int rowIndex = 0;
        for (Map<String, Object> player : players) {
            rowIndex++;
            y = drawSettlePlayerRow(g, ctx, y, player, faceUrls, rowIndex, rowIndex % 2 == 1);
        }
        return y;
    }

    private int drawSettlePlayerRow(Graphics2D g, SettleCtx ctx, int y, Map<String, Object> player,
                                    Map<String, String> faceUrls, int rowIndex, boolean altRow) {
        List<Integer> cols = ctx.cols();
        int h = ctx.rowHeight();
        int rowWidth = ctx.tableWidth();
        int doorCount = ctx.doorCount();
        int mergeIndex = 2 + doorCount;
        int tailNetIndex = mergeIndex + 2;

        String nickname = str(player.get("nickname"));
        String imUserId = str(player.get("imUserId")).trim();
        Map<?, ?> doorBets = player.get("doorBets") instanceof Map<?, ?> m ? m : Map.of();
        long net = lng(player.get("net"));
        String netText = net >= 0 ? "+" + net : String.valueOf(net);

        List<String> cells = new ArrayList<>();
        cells.add(String.valueOf(rowIndex));
        cells.add("");
        for (int door = 1; door <= doorCount; door++) {
            long amount = doorVal(doorBets, door);
            cells.add(amount > 0 ? String.valueOf(amount) : "");
        }
        cells.add(nickname);
        cells.add("");
        cells.add(netText);
        cells.add(formatBalance(player.get("balanceBefore")));
        cells.add(formatBalance(player.get("balanceAfter")));

        g.setColor(altRow ? RenderKit.GRAY_ROW : RenderKit.WHITE);
        g.fillRect(0, y, rowWidth, h);
        drawSettleGridBorders(g, ctx, y, h, true);

        ImageText.drawCenteredText(g, ReportFonts.regular(), cells.get(0), ctx.fontSm(),
            cols.get(0), y, cols.get(1) - cols.get(0), h, RenderKit.BLACK);
        ctx.kit().drawAvatarLeftCenteredName(g, ReportFonts.regular(), cols.get(1), y,
            cols.get(2) - cols.get(1), h, nickname, faceUrls.get(imUserId), ctx.fontSm(), RenderKit.BLACK);

        int colCount = Math.min(cells.size(), cols.size() - 1);
        for (int i = 2; i < colCount; i++) {
            String text = cells.get(i);
            if (i == mergeIndex + 1) continue;
            Color color = RenderKit.BLACK;
            if (i == tailNetIndex) {
                color = RenderKit.signedColor(text);
            } else if (i == tailNetIndex + 1 || i == tailNetIndex + 2) {
                color = RenderKit.remainingBalanceColor(text);
            }
            int cellX = cols.get(i);
            int cellWidth = cols.get(i + 1) - cols.get(i);
            if (i == mergeIndex) {
                cellWidth = cols.get(mergeIndex + 2) - cols.get(mergeIndex);
                ctx.kit().drawAvatarLeftCenteredName(g, ReportFonts.regular(), cellX, y, cellWidth, h,
                    nickname, faceUrls.get(imUserId), ctx.fontSm(), RenderKit.BLACK);
                continue;
            }
            ImageText.drawCenteredText(g, RenderKit.fontForCell(ReportFonts.regular(), color), text,
                ctx.fontSm(), cellX, y, cellWidth, h, color);
        }
        return y + h;
    }

    private void drawSettleGridBorders(Graphics2D g, SettleCtx ctx, int y, int h, boolean mergeGrandAndCommission) {
        List<Integer> cols = ctx.cols();
        int rowWidth = ctx.tableWidth();
        int lw = ImageBorder.widthForRowHeight(ctx.kit().scale(), h);
        ImageBorder.rectangle(g, 0, y, rowWidth, y + h, RenderKit.GRAY_BORDER, lw);
        int skipIndex = mergeGrandAndCommission ? ctx.doorsEnd() + 1 : -1;
        for (int idx = 0; idx < cols.size(); idx++) {
            if (idx == skipIndex) continue;
            ImageBorder.line(g, cols.get(idx), y, cols.get(idx), y + h, RenderKit.GRAY_BORDER, lw);
        }
    }

    /** 结算表姓名列（非居中变体）：头像左侧 + 左对齐昵称，硬裁剪。 */
    private void drawSettleAvatarAndName(Graphics2D g, RenderKit kit, int x, int y, int width, int height,
                                         String nickname, String faceUrl, int fontSize, Color nameColor) {
        int avatarSize = height;
        BufferedImage avatar = kit.loadSquareAvatar(faceUrl, avatarSize, RenderKit.GRAY_AVATAR);
        if (avatar != null) {
            g.drawImage(avatar, x, y, avatarSize, avatarSize, null);
        } else {
            g.setColor(RenderKit.GRAY_AVATAR);
            g.fillRect(x, y, avatarSize, avatarSize);
        }
        int textX = x + avatarSize + kit.px(2);
        int textBoxWidth = Math.max(0, x + width - textX - kit.px(2));
        ImageText.drawTextInBounds(g, ReportFonts.regular(), fontSize, textX, y, textBoxWidth, height,
            nameColor, nickname, false, 0);
    }

    static String formatSharePercent(double percent) {
        java.math.BigDecimal bd = java.math.BigDecimal.valueOf(percent)
            .setScale(2, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros();
        return bd.toPlainString();
    }

    // ==================== 走势图（PHP TrendChartImageService） ====================

    @SuppressWarnings("unchecked")
    public Map<String, Object> generateAndSendTrend(Map<String, Object> report, String imGroupId) {
        RenderKit kit = kit();
        int doorCount = intVal(report.get("doorCount"), 0);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) report.getOrDefault("rows", List.of());
        if (doorCount < 1 || rows.isEmpty()) {
            return err("NO_DATA", "暂无走势数据");
        }

        // 模板在 scale=3 时为 1024×963：期数/时间 = 120/180 px，六门 = 121/121/120/121/120/121 px
        int scale = kit.scale();
        int colPeriod = (int) Math.round(120.0 * scale / 3);
        int colTime = (int) Math.round(180.0 * scale / 3);
        int colDoor = kit.px(40);
        int[] referenceDoorWidths = {121, 121, 120, 121, 120, 121};
        List<Integer> doorWidths = new ArrayList<>();
        for (int door = 0; door < doorCount; door++) {
            int referenceWidth = door < referenceDoorWidths.length ? referenceDoorWidths[door] : 120;
            doorWidths.add((int) Math.round((double) referenceWidth * scale / 3));
        }
        int rowHeight = kit.px(20);
        int headerHeight = kit.px(20);
        int footerDoorHeight = kit.px(21);
        int titleHeight = kit.px(20);
        int sectionGap = kit.px(20);
        int tableWidth = colPeriod + colTime + doorWidths.stream().mapToInt(Integer::intValue).sum();
        int height = Math.max(titleHeight + headerHeight + rows.size() * rowHeight
            + sectionGap + footerDoorHeight, kit.px(120));
        // 参考图文字紧凑：仅金额保留粗体
        int fontTitle = kit.px(10);
        int fontSm = kit.px(8);
        int fontMd = kit.px(9);
        int fontAmount = kit.px(10);

        List<Integer> cols = new ArrayList<>(List.of(0, colPeriod, colPeriod + colTime));
        int offset = colPeriod + colTime;
        for (int doorWidth : doorWidths) {
            offset += doorWidth;
            cols.add(offset);
        }
        while (cols.size() < doorCount + 3) {
            offset += colDoor;
            cols.add(offset);
        }

        // 模板：标题与金额为粗体（ArialRoundedMT-Bold），表头/期数/时间为常规体，
        // 走势正文用 ArialRoundedMT-700
        ReportFonts.Id fontRegular = ReportFonts.semiBold();
        ReportFonts.Id fontAmountBold = ReportFonts.bold();
        ReportFonts.Id fontHeader = ReportFonts.regular();

        BufferedImage img = RenderKit.newCanvas(tableWidth, height);
        Graphics2D g = RenderKit.graphics(img);
        try {
            g.setColor(RenderKit.WHITE);
            g.fillRect(0, 0, tableWidth, height);

            int y = kit.drawOrangeBar(g, ReportFonts.title(), title() + " 走势图",
                tableWidth, 0, titleHeight, fontTitle);

            // 表头
            g.setColor(RenderKit.ORANGE);
            g.fillRect(0, y, tableWidth, headerHeight);
            drawTrendGridBorders(g, kit, cols, y, headerHeight, 0);
            List<String> headers = new ArrayList<>(List.of("期数", "时间"));
            for (int door = 1; door <= doorCount; door++) headers.add(door + "门");
            for (int i = 0; i < headers.size(); i++) {
                ImageText.drawCenteredText(g, fontHeader, headers.get(i), fontMd,
                    cols.get(i), y, cols.get(i + 1) - cols.get(i), headerHeight, RenderKit.WHITE);
            }
            y += headerHeight;

            // 数据行
            int rowIndex = 0;
            for (Map<String, Object> row : rows) {
                rowIndex++;
                boolean altRow = rowIndex % 2 == 1;
                boolean isPlaceholder = Boolean.TRUE.equals(row.get("placeholder"));
                int bankerDoor = isPlaceholder ? 0 : intVal(row.get("bankerDoor"), 0);

                g.setColor(altRow ? RenderKit.GRAY_ROW : RenderKit.WHITE);
                g.fillRect(0, y, tableWidth, rowHeight);
                g.setColor(RenderKit.ORANGE);
                g.fillRect(cols.get(0), y, cols.get(2) - cols.get(0), rowHeight);
                if (bankerDoor >= 1 && bankerDoor <= doorCount) {
                    int bankerCol = 2 + (bankerDoor - 1);
                    g.setColor(RenderKit.GREEN);
                    g.fillRect(cols.get(bankerCol), y, cols.get(bankerCol + 1) - cols.get(bankerCol), rowHeight);
                }
                drawTrendGridBorders(g, kit, cols, y, rowHeight, 0);

                String periodText = isPlaceholder ? "" : str(row.get("periodNo"));
                ImageText.drawCenteredText(g, fontRegular, periodText, fontSm,
                    cols.get(0), y, cols.get(1) - cols.get(0), rowHeight, RenderKit.WHITE);
                String timeText = isPlaceholder ? "" : str(row.get("time"));
                ImageText.drawCenteredText(g, fontRegular, timeText, fontSm,
                    cols.get(1), y, cols.get(2) - cols.get(1), rowHeight, RenderKit.WHITE);

                Map<?, ?> doors = row.get("doors") instanceof Map<?, ?> m ? m : Map.of();
                for (int door = 1; door <= doorCount; door++) {
                    Object cellObj = doorGet(doors, door);
                    Map<?, ?> cell = cellObj instanceof Map<?, ?> m ? m : Map.of();
                    Object amount = cell.get("amount");
                    String compare = cell.get("compare") == null ? null : String.valueOf(cell.get("compare"));
                    int colIndex = 2 + (door - 1);

                    String text;
                    if (amount != null && !String.valueOf(amount).isEmpty()) {
                        text = String.valueOf(amount);
                    } else {
                        text = isPlaceholder ? "" : "-";
                    }
                    Color textColor = RenderKit.BLACK;
                    if (door == bankerDoor) {
                        textColor = RenderKit.BLACK;
                    } else if ("player".equals(compare)) {
                        textColor = RenderKit.POSITIVE;
                    } else if ("banker".equals(compare)) {
                        textColor = RenderKit.NEGATIVE;
                    }
                    ReportFonts.Id amountFont = "-".equals(text) ? fontRegular : fontAmountBold;
                    int amountSize = "-".equals(text) ? fontSm : fontAmount;

                    // 轻微加粗：非占位数字重复绘制（x 和 x+1）
                    ImageText.drawCenteredText(g, amountFont, text, amountSize,
                        cols.get(colIndex), y, cols.get(colIndex + 1) - cols.get(colIndex), rowHeight, textColor);
                    if (!text.isEmpty() && !"-".equals(text)) {
                        ImageText.drawCenteredText(g, amountFont, text, amountSize,
                            cols.get(colIndex) + 1, y, cols.get(colIndex + 1) - cols.get(colIndex), rowHeight, textColor);
                    }
                }
                y += rowHeight;
            }

            // 主表和尾部门头之间保留一整行白色间隔
            y += sectionGap;

            // 尾部门头：橙色条仅标注各门（期数+时间列合并留白）
            g.setColor(RenderKit.ORANGE);
            g.fillRect(0, y, tableWidth, footerDoorHeight);
            drawTrendGridBorders(g, kit, cols, y, footerDoorHeight, 2);
            for (int door = 1; door <= doorCount; door++) {
                int colIndex = 2 + (door - 1);
                ImageText.drawCenteredText(g, fontHeader, door + "门", fontMd,
                    cols.get(colIndex), y, cols.get(colIndex + 1) - cols.get(colIndex),
                    footerDoorHeight, RenderKit.WHITE);
            }
        } finally {
            g.dispose();
        }
        return publishAndSend(img, imGroupId, "trend", true);
    }

    private void drawTrendGridBorders(Graphics2D g, RenderKit kit, List<Integer> cols,
                                      int y, int h, int mergedLeadCols) {
        int rowWidth = cols.get(cols.size() - 1);
        int lw = ImageBorder.widthForRowHeight(kit.scale(), h);
        ImageBorder.rectangle(g, 0, y, rowWidth, y + h, RenderKit.GRAY_BORDER, lw);
        for (int i = 0; i < cols.size(); i++) {
            if (mergedLeadCols >= 2 && i > 0 && i < mergedLeadCols) continue;
            ImageBorder.line(g, cols.get(i), y, cols.get(i), y + h, RenderKit.GRAY_BORDER, lw);
        }
    }

    // ==================== 流水账单（PHP AdminSettleBillImageService） ====================

    @SuppressWarnings("unchecked")
    public Map<String, Object> generateAndSendSettleBill(Map<String, Object> bill, String imGroupId) {
        RenderKit kit = kit();
        String title = str(bill.get("title")).isBlank() ? title() : str(bill.get("title"));

        List<Map<String, Object>> personalGroups =
            (List<Map<String, Object>>) bill.getOrDefault("personalGroups", List.of());
        List<Map<String, Object>> bankerGroups =
            (List<Map<String, Object>>) bill.getOrDefault("bankerGroups", List.of());
        List<Map<String, Object>> ledgerGroups =
            (List<Map<String, Object>>) bill.getOrDefault("ledgerGroups", List.of());

        Set<String> imUserIds = new LinkedHashSet<>();
        for (List<Map<String, Object>> groups : List.of(personalGroups, bankerGroups, ledgerGroups)) {
            for (Map<String, Object> group : groups) {
                for (Object p : (List<?>) group.getOrDefault("players", List.of())) {
                    String id = str(((Map<String, Object>) p).get("imUserId")).trim();
                    if (!id.isEmpty()) imUserIds.add(id);
                }
            }
        }
        Map<String, String> faceUrls = profiles.getAvatarUrls(new ArrayList<>(imUserIds));
        avatars.prefetch(new ArrayList<>(faceUrls.values()));

        // 流水模板：宽 394 逻辑 px；行高/表头/标题 14 逻辑 px
        int rowHeight = kit.px(14);
        int headerHeight = kit.px(14);
        int titleHeight = kit.px(14);
        int width = kit.px(394);
        int sectionGap = rowHeight;
        int fontSm = kit.px(5);
        int fontMd = kit.px(6);
        int fontTitle = kit.titleFontSize(titleHeight);

        boolean withPersonal = !personalGroups.isEmpty();
        boolean withBanker = !bankerGroups.isEmpty();
        boolean withLedger = !ledgerGroups.isEmpty();

        int height = titleHeight + headerHeight + rowHeight * 2 + sectionGap;
        if (withPersonal) {
            height += rowHeight + headerHeight + countPlayers(personalGroups) * rowHeight
                + countFooters(personalGroups) * rowHeight;
            if (withBanker || withLedger) height += sectionGap;
        }
        if (withBanker) {
            height += rowHeight + headerHeight + countPlayers(bankerGroups) * rowHeight
                + countFooters(bankerGroups) * rowHeight;
            if (withLedger) height += sectionGap;
        }
        if (withLedger) {
            height += rowHeight + headerHeight + countPlayers(ledgerGroups) * rowHeight
                + countFooters(ledgerGroups) * rowHeight;
        }
        height = Math.max(height, kit.px(200));

        // 各列边界来自流水模板采样
        List<Integer> summaryCols = new ArrayList<>();
        for (int v : kit.pxCols(0, 75, 121, 211, 257, 302)) summaryCols.add(v);
        summaryCols.add(width);
        List<Integer> personalCols = new ArrayList<>();
        for (int v : kit.pxCols(0, 75, 121, 166, 211, 257, 302, 348)) personalCols.add(v);
        personalCols.add(width);

        BufferedImage img = RenderKit.newCanvas(width, height);
        Graphics2D g = RenderKit.graphics(img);
        try {
            g.setColor(RenderKit.WHITE);
            g.fillRect(0, 0, width, height);

            int y = kit.drawOrangeBar(g, ReportFonts.title(), title + "流水", width, 0, titleHeight, fontTitle);

            // 汇总表
            y = drawBillSummaryTable(g, kit, summaryCols, y, headerHeight, rowHeight, fontMd, bill, width);
            y += sectionGap;

            if (withPersonal) {
                y = drawBillSectionTitle(g, width, rowHeight, y, fontTitle, "个人流水");
                y = drawBillPersonalTable(g, kit, personalCols, y, headerHeight, rowHeight,
                    fontSm, fontMd, personalGroups, faceUrls, width);
                if (withBanker || withLedger) y += sectionGap;
            }
            if (withBanker) {
                y = drawBillSectionTitle(g, width, rowHeight, y, fontTitle, "庄流水");
                y = drawBillBankerTable(g, kit, personalCols, y, headerHeight, rowHeight,
                    fontSm, fontMd, bankerGroups, faceUrls, width);
                if (withLedger) y += sectionGap;
            }
            if (withLedger) {
                y = drawBillSectionTitle(g, width, rowHeight, y, fontTitle, "上下分");
                drawBillLedgerTable(g, kit, personalCols, y, headerHeight, rowHeight,
                    fontSm, fontMd, ledgerGroups, faceUrls, width);
            }
        } finally {
            g.dispose();
        }
        return publishAndSend(img, imGroupId, "bill", false, true);
    }

    @SuppressWarnings("unchecked")
    private static int countPlayers(List<Map<String, Object>> groups) {
        int n = 0;
        for (Map<String, Object> group : groups) {
            n += ((List<Object>) group.getOrDefault("players", List.of())).size();
        }
        return n;
    }

    private static int countFooters(List<Map<String, Object>> groups) {
        int n = 0;
        for (Map<String, Object> group : groups) {
            if (!Boolean.TRUE.equals(group.get("omitFooter"))) n++;
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    private int drawBillSummaryTable(Graphics2D g, RenderKit kit, List<Integer> cols, int y,
                                     int hh, int rh, int fontMd, Map<String, Object> bill, int w) {
        String[] headers = {"时间", "岛数", "注数", "包费", "抽水", ""};
        int colCount = cols.size() - 1;
        for (int i = 0; i < colCount; i++) {
            g.setColor(RenderKit.ORANGE);
            g.fillRect(cols.get(i), y, cols.get(i + 1) - cols.get(i), hh);
            String label = i < headers.length ? headers[i] : "";
            if (!label.isEmpty()) {
                ImageText.drawCenteredText(g, ReportFonts.regular(), label, fontMd,
                    cols.get(i), y, cols.get(i + 1) - cols.get(i), hh, RenderKit.WHITE);
            }
        }
        ImageBorder.drawRowGrid(g, kit.scale(), 0, y, cols, hh, RenderKit.GRAY_BORDER);
        y += hh;

        Map<String, Object> current = bill.get("currentRound") instanceof Map<?, ?> m
            ? (Map<String, Object>) m : Map.of();
        drawBillFlowDataRow(g, kit, cols, y, rh, fontMd, List.of(
            str(current.get("time")),
            String.valueOf(lng(current.get("roundCount"))),
            String.valueOf(lng(current.get("betAmount"))),
            current.get("packageFee") == null ? "0.00" : String.valueOf(current.get("packageFee")),
            String.valueOf(lng(current.get("rake"))),
            ""), false);
        y += rh;

        Map<String, Object> total = bill.get("sessionTotal") instanceof Map<?, ?> m
            ? (Map<String, Object>) m : Map.of();
        drawBillFlowDataRow(g, kit, cols, y, rh, fontMd, List.of(
            "合计",
            String.valueOf(lng(total.get("roundCount"))),
            String.valueOf(lng(total.get("betAmount"))),
            total.get("packageFee") == null ? "0.00" : String.valueOf(total.get("packageFee")),
            String.valueOf(lng(total.get("rake"))),
            ""), true);
        y += rh;
        return y;
    }

    private void drawBillFlowDataRow(Graphics2D g, RenderKit kit, List<Integer> cols, int y, int rh,
                                     int fontMd, List<String> cells, boolean isTotal) {
        int w = cols.get(cols.size() - 1);
        g.setColor(isTotal ? RenderKit.GREEN : RenderKit.WHITE);
        g.fillRect(0, y, w, rh);
        ImageBorder.drawRowGrid(g, kit.scale(), 0, y, cols, rh, RenderKit.GRAY_BORDER);
        Color textColor = isTotal ? RenderKit.WHITE : RenderKit.BLACK;
        int colCount = cols.size() - 1;
        for (int i = 0; i < colCount; i++) {
            String text = i < cells.size() ? cells.get(i) : "";
            if (text.isEmpty()) continue;
            ImageText.drawCenteredText(g, ReportFonts.regular(), text, fontMd,
                cols.get(i), y, cols.get(i + 1) - cols.get(i), rh, textColor);
        }
    }

    private int drawBillSectionTitle(Graphics2D g, int w, int h, int y, int fontTitle, String title) {
        g.setColor(RenderKit.ORANGE);
        g.fillRect(0, y, w, h);
        ImageText.drawCenteredText(g, ReportFonts.regular(), title, fontTitle, 0, y, w, h, RenderKit.BLACK);
        return y + h;
    }

    @SuppressWarnings("unchecked")
    private int drawBillPersonalTable(Graphics2D g, RenderKit kit, List<Integer> cols, int y,
                                      int hh, int rh, int fontSm, int fontMd,
                                      List<Map<String, Object>> groups, Map<String, String> faceUrls, int w) {
        String[] headers = {"姓名", "岛数", "注数", "退水", "上分", "下分", "出入", "剩余积分"};
        drawBillHeaderRow(g, kit, cols, y, hh, fontMd, headers, RenderKit.GREEN, w);
        y += hh;

        int rowIndex = 0;
        for (Map<String, Object> group : groups) {
            for (Object p : (List<?>) group.getOrDefault("players", List.of())) {
                Map<String, Object> player = (Map<String, Object>) p;
                rowIndex++;
                drawBillRowBg(g, kit, cols, y, rh, rowIndex, w);

                String imUserId = str(player.get("imUserId")).trim();
                kit.drawAvatarLeftCenteredName(g, ReportFonts.regular(), cols.get(0), y,
                    cols.get(1) - cols.get(0), rh, str(player.get("nickname")),
                    faceUrls.get(imUserId), fontSm, RenderKit.BLACK);

                billCell(g, kit, cols, 1, y, rh, fontSm, String.valueOf(lng(player.get("roundCount"))), RenderKit.BLACK);
                billCell(g, kit, cols, 2, y, rh, fontSm, String.valueOf(lng(player.get("betAmount"))), RenderKit.BLACK);
                billCell(g, kit, cols, 3, y, rh, fontSm, "0", RenderKit.BLACK);

                long credit = lng(player.get("creditTotal"));
                billCell(g, kit, cols, 4, y, rh, fontSm, credit > 0 ? String.valueOf(credit) : "", RenderKit.BLACK);
                long debit = lng(player.get("debitTotal"));
                billCell(g, kit, cols, 5, y, rh, fontSm, debit > 0 ? String.valueOf(debit) : "", RenderKit.BLACK);

                long net = lng(player.get("net"));
                billCell(g, kit, cols, 6, y, rh, fontSm, String.valueOf(net), RenderKit.signedColor(net));

                long bal = player.get("balanceAfter") == null ? 0 : lng(player.get("balanceAfter"));
                billCell(g, kit, cols, 7, y, rh, fontSm, String.valueOf(bal), RenderKit.signedColor(bal));
                y += rh;
            }

            if (Boolean.TRUE.equals(group.get("omitFooter"))) continue;
            Map<String, Object> totals = group.get("totals") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
            String groupKey = str(group.getOrDefault("groupKey", "0"));
            g.setColor(RenderKit.GREEN);
            g.fillRect(0, y, w, rh);
            ImageBorder.drawRowGrid(g, kit.scale(), 0, y, cols, rh, RenderKit.GRAY_BORDER);
            billCell(g, kit, cols, 0, y, rh, fontSm, "合计(组:" + groupKey + ")", RenderKit.WHITE);
            billCell(g, kit, cols, 1, y, rh, fontSm, String.valueOf(lng(totals.get("roundCount"))), RenderKit.WHITE);
            billCell(g, kit, cols, 2, y, rh, fontSm, String.valueOf(lng(totals.get("betAmount"))), RenderKit.WHITE);
            billCell(g, kit, cols, 3, y, rh, fontSm, "0", RenderKit.WHITE);
            billCell(g, kit, cols, 4, y, rh, fontSm, String.valueOf(lng(totals.get("creditTotal"))), RenderKit.WHITE);
            billCell(g, kit, cols, 5, y, rh, fontSm, String.valueOf(lng(totals.get("debitTotal"))), RenderKit.WHITE);
            billCell(g, kit, cols, 6, y, rh, fontSm, String.valueOf(lng(totals.get("net"))), RenderKit.WHITE);
            billCell(g, kit, cols, 7, y, rh, fontSm, String.valueOf(lng(totals.get("balanceAfter"))), RenderKit.WHITE);
            y += rh;
        }
        return y;
    }

    @SuppressWarnings("unchecked")
    private int drawBillBankerTable(Graphics2D g, RenderKit kit, List<Integer> cols, int y,
                                    int hh, int rh, int fontSm, int fontMd,
                                    List<Map<String, Object>> groups, Map<String, String> faceUrls, int w) {
        String[] headers = {"姓名", "岛数", "注数", "水", "", "", "出入", "剩余积分"};
        drawBillHeaderRow(g, kit, cols, y, hh, fontMd, headers, RenderKit.ORANGE, w);
        y += hh;

        int rowIndex = 0;
        for (Map<String, Object> group : groups) {
            for (Object p : (List<?>) group.getOrDefault("players", List.of())) {
                Map<String, Object> player = (Map<String, Object>) p;
                rowIndex++;
                drawBillRowBg(g, kit, cols, y, rh, rowIndex, w);

                String imUserId = str(player.get("imUserId")).trim();
                kit.drawAvatarLeftCenteredName(g, ReportFonts.regular(), cols.get(0), y,
                    cols.get(1) - cols.get(0), rh, str(player.get("nickname")),
                    faceUrls.get(imUserId), fontSm, RenderKit.BLACK);

                billCell(g, kit, cols, 1, y, rh, fontSm, String.valueOf(lng(player.get("roundCount"))), RenderKit.BLACK);
                billCell(g, kit, cols, 2, y, rh, fontSm, String.valueOf(lng(player.get("betAmount"))), RenderKit.BLACK);
                billCell(g, kit, cols, 3, y, rh, fontSm, String.valueOf(lng(player.get("rebate"))), RenderKit.BLACK);

                long net = lng(player.get("net"));
                billCell(g, kit, cols, 6, y, rh, fontSm, String.valueOf(net), RenderKit.signedColor(net));
                long bal = player.get("balanceAfter") == null ? 0 : lng(player.get("balanceAfter"));
                billCell(g, kit, cols, 7, y, rh, fontSm, String.valueOf(bal), RenderKit.signedColor(bal));
                y += rh;
            }

            if (Boolean.TRUE.equals(group.get("omitFooter"))) continue;
            Map<String, Object> totals = group.get("totals") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
            String groupKey = str(group.getOrDefault("groupKey", "0"));
            g.setColor(RenderKit.GREEN);
            g.fillRect(0, y, w, rh);
            ImageBorder.drawRowGrid(g, kit.scale(), 0, y, cols, rh, RenderKit.GRAY_BORDER);
            billCell(g, kit, cols, 0, y, rh, fontSm, "合计(组:" + groupKey + ")", RenderKit.WHITE);
            billCell(g, kit, cols, 1, y, rh, fontSm, String.valueOf(lng(totals.get("roundCount"))), RenderKit.WHITE);
            billCell(g, kit, cols, 2, y, rh, fontSm, String.valueOf(lng(totals.get("betAmount"))), RenderKit.WHITE);
            billCell(g, kit, cols, 3, y, rh, fontSm, String.valueOf(lng(totals.get("rebate"))), RenderKit.WHITE);
            billCell(g, kit, cols, 6, y, rh, fontSm, String.valueOf(lng(totals.get("net"))), RenderKit.WHITE);
            billCell(g, kit, cols, 7, y, rh, fontSm, String.valueOf(lng(totals.get("balanceAfter"))), RenderKit.WHITE);
            y += rh;
        }
        return y;
    }

    @SuppressWarnings("unchecked")
    private int drawBillLedgerTable(Graphics2D g, RenderKit kit, List<Integer> cols, int y,
                                    int hh, int rh, int fontSm, int fontMd,
                                    List<Map<String, Object>> groups, Map<String, String> faceUrls, int w) {
        String[] headers = {"姓名", "", "", "", "上分", "下分", "出入", "剩余积分"};
        drawBillHeaderRow(g, kit, cols, y, hh, fontMd, headers, RenderKit.ORANGE, w);
        y += hh;

        int rowIndex = 0;
        for (Map<String, Object> group : groups) {
            for (Object p : (List<?>) group.getOrDefault("players", List.of())) {
                Map<String, Object> row = (Map<String, Object>) p;
                rowIndex++;
                drawBillRowBg(g, kit, cols, y, rh, rowIndex, w);

                String imUserId = str(row.get("imUserId")).trim();
                kit.drawAvatarLeftCenteredName(g, ReportFonts.regular(), cols.get(0), y,
                    cols.get(1) - cols.get(0), rh, str(row.get("nickname")),
                    faceUrls.get(imUserId), fontSm, RenderKit.BLACK);

                long credit = lng(row.get("creditTotal"));
                billCell(g, kit, cols, 4, y, rh, fontSm, credit > 0 ? String.valueOf(credit) : "", RenderKit.BLACK);
                long debit = lng(row.get("debitTotal"));
                billCell(g, kit, cols, 5, y, rh, fontSm, debit > 0 ? String.valueOf(debit) : "", RenderKit.BLACK);

                long net = lng(row.get("net"));
                if (credit > 0 && debit > 0) {
                    billCell(g, kit, cols, 6, y, rh, fontSm, String.valueOf(net), RenderKit.signedColor(net));
                }
                long bal = row.get("balanceAfter") == null ? 0 : lng(row.get("balanceAfter"));
                billCell(g, kit, cols, 7, y, rh, fontSm, String.valueOf(bal), RenderKit.signedColor(bal));
                y += rh;
            }

            if (Boolean.TRUE.equals(group.get("omitFooter"))) continue;
            Map<String, Object> totals = group.get("totals") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
            String groupKey = str(group.getOrDefault("groupKey", "0"));
            g.setColor(RenderKit.GREEN);
            g.fillRect(0, y, w, rh);
            ImageBorder.drawRowGrid(g, kit.scale(), 0, y, cols, rh, RenderKit.GRAY_BORDER);
            billCell(g, kit, cols, 0, y, rh, fontSm, "合计(组:" + groupKey + ")", RenderKit.WHITE);
            billCell(g, kit, cols, 4, y, rh, fontSm, String.valueOf(lng(totals.get("creditTotal"))), RenderKit.WHITE);
            billCell(g, kit, cols, 5, y, rh, fontSm, String.valueOf(lng(totals.get("debitTotal"))), RenderKit.WHITE);
            long totalCredit = lng(totals.get("creditTotal"));
            long totalDebit = lng(totals.get("debitTotal"));
            if (totalCredit > 0 && totalDebit > 0) {
                billCell(g, kit, cols, 6, y, rh, fontSm, String.valueOf(lng(totals.get("net"))), RenderKit.WHITE);
            }
            billCell(g, kit, cols, 7, y, rh, fontSm, String.valueOf(lng(totals.get("balanceAfter"))), RenderKit.WHITE);
            y += rh;
        }
        return y;
    }

    private void drawBillHeaderRow(Graphics2D g, RenderKit kit, List<Integer> cols, int y, int hh,
                                   int fontMd, String[] headers, Color lastCellColor, int w) {
        g.setColor(RenderKit.ORANGE);
        g.fillRect(0, y, w, hh);
        int lastIndex = cols.size() - 2;
        g.setColor(lastCellColor);
        g.fillRect(cols.get(lastIndex), y, cols.get(lastIndex + 1) - cols.get(lastIndex), hh);
        ImageBorder.drawRowGrid(g, kit.scale(), 0, y, cols, hh, RenderKit.GRAY_BORDER);
        for (int i = 0; i < headers.length; i++) {
            if (headers[i].isEmpty()) continue;
            ImageText.drawCenteredText(g, ReportFonts.regular(), headers[i], fontMd,
                cols.get(i), y, cols.get(i + 1) - cols.get(i), hh, RenderKit.WHITE);
        }
    }

    private void drawBillRowBg(Graphics2D g, RenderKit kit, List<Integer> cols, int y, int rh, int rowIndex, int w) {
        g.setColor(rowIndex % 2 == 1 ? RenderKit.WHITE : RenderKit.GRAY_ROW);
        g.fillRect(0, y, w, rh);
        ImageBorder.drawRowGrid(g, kit.scale(), 0, y, cols, rh, RenderKit.GRAY_BORDER);
    }

    private void billCell(Graphics2D g, RenderKit kit, List<Integer> cols, int i, int y, int rh,
                          int fontSm, String text, Color color) {
        // 积分/盈亏彩色数字换半粗体（PHP drawCenteredText with isScoreColor）
        kit.drawCenteredTextScoreAware(g, ReportFonts.regular(), text, fontSm,
            cols.get(i), y, cols.get(i + 1) - cols.get(i), rh, color);
    }

    // ==================== 上传发送 ====================

    private Map<String, Object> publishAndSend(BufferedImage img, String imGroupId, String tag, boolean gameGroupDefault) {
        return publishAndSend(img, imGroupId, tag, gameGroupDefault, false);
    }

    private Map<String, Object> publishAndSend(BufferedImage img, String imGroupId, String tag,
                                               boolean gameGroupDefault, boolean adminStatsDefault) {
        try {
            // 内存生成 JPG 后直传 OSS，不落本地磁盘；对象由主服务定时清理（默认 7 天）
            byte[] bytes = toJpegBytes(img, props.getImage().getJpegQuality() / 100f);
            String name = "report-" + tag + "-" + UUID.randomUUID().toString().replace("-", "") + ".jpg";
            String ossUrl = oss.uploadJpeg(bytes, name);
            if (ossUrl == null || ossUrl.isBlank()) {
                return err("OSS_UPLOAD_FAILED", "图片上传 OSS 失败");
            }
            ImService.PublishedImage published = new ImService.PublishedImage(
                ossUrl, ImService.md5Hex(bytes), bytes.length, img.getWidth(), img.getHeight());
            String group = imGroupId;
            if (group == null || group.isBlank()) {
                group = adminStatsDefault ? im.getAdminStatsGroupId() : im.getGameGroupId();
            }
            if (group == null || group.isBlank()) {
                Map<String, Object> out = err("NO_IM_GROUP", "未配置目标 IM 群");
                out.put("url", ossUrl);
                return out;
            }
            Long msgSeq = im.sendImageToGroup(group, published);
            Map<String, Object> out = new LinkedHashMap<>();
            if (msgSeq == null) {
                out.put("ok", false);
                out.put("code", "IM_SEND_FAILED");
                out.put("message", "图片发送失败");
                out.put("url", ossUrl);
                out.put("imGroupId", group);
                return out;
            }
            out.put("ok", true);
            out.put("sent", true);
            out.put("imGroupId", group);
            out.put("url", ossUrl);
            out.put("msgSeq", msgSeq);
            return out;
        } catch (Exception e) {
            log.warn("report image failed tag={} err={}", tag, e.getMessage());
            return err("IMAGE_FAILED", e.getMessage() == null ? "图片生成失败" : e.getMessage());
        }
    }

    private static byte[] toJpegBytes(BufferedImage img, float quality) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(256 * 1024);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(buffer)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(Math.max(0.5f, Math.min(1f, quality)));
            }
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return buffer.toByteArray();
    }

    // ==================== 通用工具 ====================

    private RenderKit kit() {
        int scale = Math.max(1, Math.min(4, props.getImage().getScale()));
        return new RenderKit(scale, avatars);
    }

    private String title() {
        String t = props.getImage().getBetReportTitle();
        return t == null || t.isBlank() ? "三公" : t.trim();
    }

    private Map<String, String> fetchFaceUrls(List<Map<String, Object>> rows, String idKey) {
        List<String> imUserIds = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String id = str(row.get(idKey)).trim();
            if (!id.isEmpty()) imUserIds.add(id);
        }
        Map<String, String> faceUrls = profiles.getAvatarUrls(imUserIds);
        avatars.prefetch(new ArrayList<>(faceUrls.values()));
        return faceUrls;
    }

    private static Map<String, Object> err(String code, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("code", code);
        out.put("message", message);
        return out;
    }

    private static Rectangle2D inkBounds(ReportFonts.Id font, String text, int fontSize) {
        FontRenderContext frc = new FontRenderContext(null, true, false);
        GlyphVector gv = ReportFonts.derive(font, fontSize).createGlyphVector(frc, text);
        return gv.getVisualBounds();
    }

    private static String formatBalance(Object balance) {
        if (balance == null || String.valueOf(balance).isEmpty()) {
            return "";
        }
        return String.valueOf(lng(balance));
    }

    private static long doorVal(Map<?, ?> map, int door) {
        Object v = doorGet(map, door);
        return v == null ? 0 : lng(v);
    }

    private static String doorStr(Map<?, ?> map, int door) {
        Object v = doorGet(map, door);
        return v == null ? "" : String.valueOf(v);
    }

    private static Object doorGet(Map<?, ?> map, int door) {
        Object v = map.get(door);
        if (v == null) v = map.get((long) door);
        if (v == null) v = map.get(String.valueOf(door));
        return v;
    }

    static long lng(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v == null) return 0;
        try {
            return (long) Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseLongSafe(String v) {
        try {
            return (long) Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int intVal(Object v, int def) {
        if (v instanceof Number n) return n.intValue();
        if (v == null) return def;
        try {
            return (int) Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double dbl(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v == null) return 0;
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
