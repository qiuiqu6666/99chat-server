package com.chat99.sangong.image;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 与 PHP ImageTextHelper 逐行对齐的文本绘制：
 * - 数字与汉字混排自动加空格；
 * - Arial Rounded 不含汉字，按字符拆「拉丁段 / Noto 回退段」；
 * - emoji 用 Twemoji PNG 贴图；
 * - 宽度取「墨迹宽度」与「原点到右侧墨迹边缘」较大值（与 imagettfbbox 用法一致）。
 * 字号参数一律沿用 GD pt（内部换算为 Java px）。
 */
public final class ImageText {
    private static final Pattern HAN_DIGIT = Pattern.compile("(\\p{IsHan})(\\d)");
    private static final Pattern DIGIT_HAN = Pattern.compile("(\\d)(\\p{IsHan})");
    private static final Pattern COLON_DIGIT = Pattern.compile("(?<!\\d)(:)(\\d)");
    private static final FontRenderContext FRC =
        new FontRenderContext(null, RenderingHints.VALUE_TEXT_ANTIALIAS_ON, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);

    private static volatile EmojiAssets emojiAssets;

    private ImageText() {}

    public static void configureEmojiAssets(EmojiAssets assets) {
        emojiAssets = assets;
    }

    public static String normalizeForGd(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        text = HAN_DIGIT.matcher(text).replaceAll("$1 $2");
        text = DIGIT_HAN.matcher(text).replaceAll("$1 $2");
        text = COLON_DIGIT.matcher(text).replaceAll("$1 $2");
        return text;
    }

    public static void drawText(Graphics2D g, ReportFonts.Id font, int fontSize,
                                int x, int baselineY, Color color, String text) {
        drawText(g, font, fontSize, x, baselineY, color, text, true);
    }

    public static void drawText(Graphics2D g, ReportFonts.Id font, int fontSize,
                                int x, int baselineY, Color color, String text, boolean spaceNumbers) {
        if (spaceNumbers) {
            text = normalizeForGd(text);
        }
        if (text == null || text.isEmpty()) {
            return;
        }
        int cursorX = x;
        for (Token token : tokens(text)) {
            if (token.emoji) {
                cursorX += drawEmojiToken(g, token.text, cursorX, baselineY, fontSize);
                continue;
            }
            for (Run run : fontRuns(font, token.text)) {
                Font awt = ReportFonts.derive(run.font, fontSize);
                g.setFont(awt);
                g.setColor(color);
                g.drawString(run.text, cursorX, baselineY);
                cursorX += plainTextWidth(run.font, run.text, fontSize);
            }
        }
    }

    public static int textWidth(ReportFonts.Id font, String text, int fontSize) {
        return textWidth(font, text, fontSize, true);
    }

    public static int textWidth(ReportFonts.Id font, String text, int fontSize, boolean spaceNumbers) {
        if (spaceNumbers) {
            text = normalizeForGd(text);
        }
        return plainTextWidthTotal(font, text, fontSize);
    }

    private static int plainTextWidthTotal(ReportFonts.Id font, String text, int fontSize) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int width = 0;
        for (Token token : tokens(text)) {
            if (token.emoji) {
                width += emojiWidth(fontSize);
                continue;
            }
            for (Run run : fontRuns(font, token.text)) {
                width += plainTextWidth(run.font, run.text, fontSize);
            }
        }
        return width;
    }

    public static String truncateToWidth(ReportFonts.Id font, String text, int fontSize, int maxWidth) {
        text = normalizeForGd(text);
        if (text.isEmpty() || maxWidth <= 0) {
            return "";
        }
        if (textWidth(font, text, fontSize) <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        List<Token> toks = new ArrayList<>(tokens(text));
        while (!toks.isEmpty()) {
            int lastIndex = toks.size() - 1;
            Token last = toks.get(lastIndex);
            if (!last.emoji && last.text.codePointCount(0, last.text.length()) > 1) {
                int cut = last.text.offsetByCodePoints(last.text.length(), -1);
                toks.set(lastIndex, new Token(false, last.text.substring(0, cut)));
            } else {
                toks.remove(lastIndex);
            }
            StringBuilder sb = new StringBuilder();
            for (Token t : toks) {
                sb.append(t.text);
            }
            String candidate = sb.toString();
            if (candidate.isEmpty()) {
                break;
            }
            if (textWidth(font, candidate + ellipsis, fontSize) <= maxWidth) {
                return candidate + ellipsis;
            }
        }
        return textWidth(font, ellipsis, fontSize) <= maxWidth ? ellipsis : "";
    }

    /** 单元格内垂直居中基线；中文表头按实际回退字体测高（与 PHP centeredBaselineY 对齐）。 */
    public static int centeredBaselineY(int cellY, int cellHeight, ReportFonts.Id font,
                                        String text, int fontSize) {
        return centeredBaselineY(cellY, cellHeight, font, text, fontSize, true);
    }

    public static int centeredBaselineY(int cellY, int cellHeight, ReportFonts.Id font,
                                        String text, int fontSize, boolean spaceNumbers) {
        if (spaceNumbers) {
            text = normalizeForGd(text);
        }
        if (text == null || text.isEmpty() || cellHeight <= 0) {
            return cellY + cellHeight / 2;
        }
        String sample = firstMeasurableSample(text);
        Run run = fontRuns(font, sample).get(0);
        Rectangle2D ink = inkBounds(run.font, run.text, fontSize);
        // bbox[1] = maxY（基线下缘）、bbox[7] = minY（基线上缘，负值）
        double maxY = ink.getMaxY();
        double minY = ink.getMinY();
        return cellY + (int) Math.round((cellHeight - (maxY - minY)) / 2.0 - minY);
    }

    /** 在矩形区域内水平/垂直居中绘制文本。 */
    public static void drawCenteredText(Graphics2D g, ReportFonts.Id font, String text, int fontSize,
                                        int x, int y, int width, int height, Color color) {
        drawCenteredText(g, font, text, fontSize, x, y, width, height, color, true);
    }

    public static void drawCenteredText(Graphics2D g, ReportFonts.Id font, String text, int fontSize,
                                        int x, int y, int width, int height, Color color, boolean spaceNumbers) {
        String display = spaceNumbers ? normalizeForGd(text) : text;
        if (display == null || display.isEmpty() || width <= 0 || height <= 0) {
            return;
        }
        int textWidth = plainTextWidthTotal(font, display, fontSize);
        int drawX = x + (width - textWidth) / 2;
        int drawY = centeredBaselineY(y, height, font, display, fontSize, false);
        drawText(g, font, fontSize, drawX, drawY, color, display, false);
    }

    /** 在固定区域内绘制文本（先截断，再硬裁剪，避免溢出单元格）。 */
    public static void drawTextInBounds(Graphics2D g, ReportFonts.Id font, int fontSize,
                                        int x, int y, int width, int height, Color color,
                                        String text, boolean center, int padding) {
        if (width <= 0 || height <= 0) {
            return;
        }
        int maxWidth = Math.max(0, width - padding * 2);
        String label = truncateToWidth(font, text, fontSize, maxWidth);
        if (label.isEmpty()) {
            return;
        }
        int textWidth = textWidth(font, label, fontSize);
        int baselineY = centeredBaselineY(0, height, font, label, fontSize);
        int drawX = padding;
        if (center) {
            drawX = padding + (maxWidth - textWidth) / 2;
        }
        Graphics2D clipped = (Graphics2D) g.create();
        try {
            clipped.clipRect(x, y, width, height);
            clipped.translate(x, y);
            drawText(clipped, font, fontSize, drawX, baselineY, color, label);
        } finally {
            clipped.dispose();
        }
    }

    // ---------- 内部实现 ----------

    private static String firstMeasurableSample(String text) {
        for (Token token : tokens(text)) {
            if (!token.emoji && !token.text.isEmpty()) {
                return token.text;
            }
        }
        return "国";
    }

    private record Token(boolean emoji, String text) {}

    private record Run(ReportFonts.Id font, String text) {}

    private static List<Token> tokens(String text) {
        int[] cps = text.codePoints().toArray();
        List<Token> tokens = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        int count = cps.length;

        for (int i = 0; i < count; i++) {
            int cp = cps[i];
            Integer next = i + 1 < count ? cps[i + 1] : null;

            // 键帽 emoji：#/*/0-9 + FE0F? + 20E3
            if (isKeycapStart(cp) && next != null && (next == 0xFE0F || next == 0x20E3)) {
                StringBuilder emoji = new StringBuilder().appendCodePoint(cp);
                if (next == 0xFE0F) {
                    emoji.appendCodePoint(next);
                    i++;
                    next = i + 1 < count ? cps[i + 1] : null;
                }
                if (next != null && next == 0x20E3) {
                    emoji.appendCodePoint(next);
                    i++;
                    pushText(tokens, buffer);
                    tokens.add(new Token(true, emoji.toString()));
                    continue;
                }
            }

            if (!isEmojiBase(cp)) {
                if (!isEmojiModifier(cp) && !isVariationSelector(cp)) {
                    buffer.appendCodePoint(cp);
                }
                continue;
            }

            pushText(tokens, buffer);
            StringBuilder emoji = new StringBuilder().appendCodePoint(cp);
            while (i + 1 < count) {
                int lookahead = cps[i + 1];
                if (isVariationSelector(lookahead) || isEmojiModifier(lookahead)) {
                    emoji.appendCodePoint(lookahead);
                    i++;
                    continue;
                }
                if (lookahead == 0x200D && i + 2 < count) {
                    emoji.appendCodePoint(lookahead).appendCodePoint(cps[i + 2]);
                    i += 2;
                    continue;
                }
                if (isRegionalIndicator(cp) && isRegionalIndicator(lookahead)) {
                    emoji.appendCodePoint(lookahead);
                    i++;
                }
                break;
            }
            tokens.add(new Token(true, emoji.toString()));
        }
        pushText(tokens, buffer);
        return tokens;
    }

    private static void pushText(List<Token> tokens, StringBuilder buffer) {
        if (buffer.length() > 0) {
            tokens.add(new Token(false, buffer.toString()));
            buffer.setLength(0);
        }
    }

    private static boolean isEmojiBase(int cp) {
        return (cp >= 0x1F000 && cp <= 0x1FAFF)
            || (cp >= 0x2600 && cp <= 0x27BF)
            || (cp >= 0x2300 && cp <= 0x23FF)
            || (cp >= 0x2B00 && cp <= 0x2BFF)
            || isRegionalIndicator(cp);
    }

    private static boolean isEmojiModifier(int cp) {
        return cp >= 0x1F3FB && cp <= 0x1F3FF;
    }

    private static boolean isVariationSelector(int cp) {
        return cp == 0xFE0E || cp == 0xFE0F;
    }

    private static boolean isRegionalIndicator(int cp) {
        return cp >= 0x1F1E6 && cp <= 0x1F1FF;
    }

    private static boolean isKeycapStart(int cp) {
        return cp == '#' || cp == '*' || (cp >= '0' && cp <= '9');
    }

    private static List<Run> fontRuns(ReportFonts.Id font, String text) {
        ReportFonts.Id fallback = ReportFonts.isArialRounded(font) ? ReportFonts.cjkFallback(font) : null;
        if (fallback == null || text.isEmpty()) {
            return List.of(new Run(font, text));
        }
        List<Run> runs = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        ReportFonts.Id bufferFont = null;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            ReportFonts.Id runFont = (cp >= 0x20 && cp <= 0x7E) ? font : fallback;
            if (bufferFont != null && runFont != bufferFont && buffer.length() > 0) {
                runs.add(new Run(bufferFont, buffer.toString()));
                buffer.setLength(0);
            }
            bufferFont = runFont;
            buffer.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        if (buffer.length() > 0 && bufferFont != null) {
            runs.add(new Run(bufferFont, buffer.toString()));
        }
        return runs.isEmpty() ? List.of(new Run(font, text)) : runs;
    }

    private static Rectangle2D inkBounds(ReportFonts.Id font, String text, int fontSize) {
        Font awt = ReportFonts.derive(font, fontSize);
        GlyphVector gv = awt.createGlyphVector(FRC, text);
        return gv.getVisualBounds();
    }

    /** 取「墨迹宽度」与「原点到右侧墨迹边缘」的较大值（对齐 PHP plainTextWidth）。 */
    private static int plainTextWidth(ReportFonts.Id font, String text, int fontSize) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        Rectangle2D ink = inkBounds(font, text, fontSize);
        int width = (int) Math.round(ink.getWidth());
        int rightEdge = (int) Math.round(ink.getMaxX());
        return Math.max(Math.abs(width), rightEdge);
    }

    private static int emojiWidth(int fontSize) {
        return Math.max(12, fontSize + 5);
    }

    private static int drawEmojiToken(Graphics2D g, String emoji, int x, int baselineY, int fontSize) {
        int width = emojiWidth(fontSize);
        EmojiAssets assets = emojiAssets;
        BufferedImage image = assets != null ? assets.loadImage(emoji) : null;
        if (image == null) {
            return width;
        }
        int size = Math.max(12, fontSize + 5);
        int dstY = baselineY - size + 3;
        g.drawImage(image, x, dstY, size, size, null);
        return width;
    }
}
