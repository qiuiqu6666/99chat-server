package com.chat99.sangong.image;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * 五类报表共用的绘制工具（对齐 PHP ImageTitleHelper / ImagePlayerCellHelper /
 * ImageScoreColorHelper / ImageScaleHelper 的组合行为）。
 */
public final class RenderKit {
    public static final Color WHITE = Color.WHITE;
    public static final Color BLACK = Color.BLACK;
    public static final Color ORANGE = new Color(255, 113, 31);
    public static final Color GREEN = new Color(68, 127, 33);
    public static final Color GRAY_BORDER = ImageBorder.COLOR;
    public static final Color GRAY_ROW = new Color(225, 225, 225);
    public static final Color GRAY_AVATAR = new Color(200, 200, 200);
    public static final Color BANKER_TEXT = new Color(35, 18, 242);
    public static final Color POSITIVE = new Color(0, 255, 0);
    public static final Color NEGATIVE = new Color(255, 0, 0);

    private final int scale;
    private final AvatarCache avatars;

    public RenderKit(int scale, AvatarCache avatars) {
        this.scale = scale;
        this.avatars = avatars;
    }

    public int scale() {
        return scale;
    }

    public int px(int v) {
        return v * scale;
    }

    public int[] pxCols(int... values) {
        int[] out = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[i] * scale;
        }
        return out;
    }

    public static BufferedImage newCanvas(int width, int height) {
        return new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_RGB);
    }

    public static Graphics2D graphics(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        return g;
    }

    /** 顶栏主标题字号（PHP ImageTitleHelper::fontSize）。 */
    public int titleFontSize(int titleHeightPx) {
        int compact = px(14);
        int trend = px(20);
        if (titleHeightPx <= compact) {
            return px(6);
        }
        if (titleHeightPx <= trend) {
            return px(9);
        }
        return px(10);
    }

    /** 橙底黑字顶栏：✨【标题】✨（PHP ImageTitleHelper::drawOrangeBar）。 */
    public int drawOrangeBar(Graphics2D g, ReportFonts.Id font, String coreText,
                             int width, int y, int height, int fontSize) {
        g.setColor(ORANGE);
        g.fillRect(0, y, width, height);

        String mainText = "✨【" + coreText + "】";
        String trailStar = "✨";
        int starGap = px(5);
        int mainWidth = ImageText.textWidth(font, mainText, fontSize);
        int starWidth = ImageText.textWidth(font, trailStar, fontSize);
        int groupWidth = mainWidth + starGap + starWidth;
        int groupStart = (width - groupWidth) / 2;
        int baselineY = ImageText.centeredBaselineY(y, height, font, mainText, fontSize);

        ImageText.drawText(g, font, fontSize, groupStart, baselineY, BLACK, mainText);
        ImageText.drawText(g, font, fontSize, groupStart + mainWidth + starGap, baselineY, BLACK, trailStar);
        return y + height;
    }

    public static boolean isScoreColor(Color color) {
        return POSITIVE.equals(color) || NEGATIVE.equals(color);
    }

    /** 积分/盈亏彩色数字换半粗体（PHP fontForCell / drawCenteredText with score color）。 */
    public static ReportFonts.Id fontForCell(ReportFonts.Id bodyFont, Color color) {
        return isScoreColor(color) ? ReportFonts.semiBold() : bodyFont;
    }

    /** 居中文本；score 颜色自动换半粗体。 */
    public void drawCenteredTextScoreAware(Graphics2D g, ReportFonts.Id font, String text, int fontSize,
                                           int x, int y, int width, int height, Color color) {
        ImageText.drawCenteredText(g, fontForCell(font, color), text, fontSize, x, y, width, height, color);
    }

    public BufferedImage loadSquareAvatar(String url, int size, Color placeholderColor) {
        return avatars.loadSquare(url, size, placeholderColor);
    }

    /** 玩家姓名列：头像固定左侧，昵称在剩余区域居中（PHP ImagePlayerCellHelper）。 */
    public void drawAvatarLeftCenteredName(Graphics2D g, ReportFonts.Id font,
                                           int x, int y, int width, int height,
                                           String nickname, String faceUrl,
                                           int fontSize, Color textColor) {
        int avatarSize = height;
        BufferedImage avatar = loadSquareAvatar(faceUrl, avatarSize, GRAY_AVATAR);
        if (avatar != null) {
            g.drawImage(avatar, x, y, avatarSize, avatarSize, null);
        } else {
            g.setColor(GRAY_AVATAR);
            g.fillRect(x, y, avatarSize, avatarSize);
        }
        String label = ImageText.normalizeForGd(nickname == null ? "" : nickname);
        if (label.isEmpty()) {
            return;
        }
        int textX = x + avatarSize;
        int textWidth = Math.max(0, width - avatarSize);
        ImageText.drawTextInBounds(g, font, fontSize, textX, y, textWidth, height,
            textColor, label, true, px(2));
    }

    /** 带符号数字颜色（PHP signedColor：按文本判断）。 */
    public static Color signedColor(String text) {
        if (text != null && !text.isEmpty()) {
            double v;
            try {
                v = Double.parseDouble(text.replace("+", ""));
            } catch (NumberFormatException e) {
                v = text.charAt(0) == '-' ? -1 : 0;
            }
            if (text.charAt(0) == '-' || v < 0) {
                return NEGATIVE;
            }
            if (v > 0) {
                return POSITIVE;
            }
        }
        return BLACK;
    }

    public static Color signedColor(long value) {
        if (value < 0) {
            return NEGATIVE;
        }
        if (value > 0) {
            return POSITIVE;
        }
        return BLACK;
    }

    /** 剩余积分颜色（PHP remainingBalanceColor：按整数判断）。 */
    public static Color remainingBalanceColor(String text) {
        long v = parseLong(text);
        if (v < 0) {
            return NEGATIVE;
        }
        if (v > 0) {
            return POSITIVE;
        }
        return BLACK;
    }

    private static long parseLong(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(text.replace("+", "").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
