package com.chat99.sangong.image;

import java.awt.Font;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 报表字体（与 PHP ImageFontHelper 对齐）：
 * 正文 ArialRoundedMT-300、主副标题 ArialRoundedMT-700、粗体 ArialRoundedMT-Bold，
 * 汉字回退 NotoSansSC-Regular / NotoSansSC-Bold。
 * GD 的字号单位是 pt（96dpi），Java2D 是 px（72dpi），换算系数 4/3。
 */
public final class ReportFonts {

    public enum Id { ARIAL_300, ARIAL_700, ARIAL_BOLD, NOTO_REGULAR, NOTO_BOLD }

    private static final Map<Id, Font> BASE = new EnumMap<>(Id.class);
    private static final Map<String, Font> DERIVED = new ConcurrentHashMap<>();

    static {
        BASE.put(Id.ARIAL_300, load("fonts/ArialRoundedMT-300.ttf"));
        BASE.put(Id.ARIAL_700, load("fonts/ArialRoundedMT-700.ttf"));
        BASE.put(Id.ARIAL_BOLD, load("fonts/ArialRoundedMT-Bold.ttf"));
        BASE.put(Id.NOTO_REGULAR, load("fonts/NotoSansSC-Regular.otf"));
        BASE.put(Id.NOTO_BOLD, load("fonts/NotoSansSC-Bold.otf"));
    }

    private ReportFonts() {}

    /** 正文（PHP resolveRegular）。 */
    public static Id regular() { return Id.ARIAL_300; }

    /** 积分/盈亏彩色数字（PHP resolveSemiBold）。 */
    public static Id semiBold() { return Id.ARIAL_700; }

    /** 粗体（PHP resolveBold）。 */
    public static Id bold() { return Id.ARIAL_BOLD; }

    /** 顶栏标题字体 = 常规体（PHP ImageTitleHelper::resolveFont）。 */
    public static Id title() { return Id.ARIAL_300; }

    /**
     * Arial Rounded 系列不含汉字，回退 Noto；仅显式 Bold 回退 Noto Bold，
     * 700 作为常规文字使用，汉字保持常规字重（与 PHP cjkFallbackFont 一致）。
     */
    public static Id cjkFallback(Id id) {
        return switch (id) {
            case ARIAL_BOLD -> Id.NOTO_BOLD;
            case ARIAL_300, ARIAL_700 -> Id.NOTO_REGULAR;
            default -> null;
        };
    }

    public static boolean isArialRounded(Id id) {
        return id == Id.ARIAL_300 || id == Id.ARIAL_700 || id == Id.ARIAL_BOLD;
    }

    /** 以 GD pt 字号取 AWT Font（自动换算 96dpi -> px）。 */
    public static Font derive(Id id, int gdPtSize) {
        String key = id.name() + ':' + gdPtSize;
        return DERIVED.computeIfAbsent(key, k -> BASE.get(id).deriveFont(gdPtSize * 96f / 72f));
    }

    private static Font load(String resource) {
        try (InputStream in = ReportFonts.class.getClassLoader().getResourceAsStream(resource)) {
            if (in != null) {
                return Font.createFont(Font.TRUETYPE_FONT, in);
            }
        } catch (Exception ignored) {
            // 走 SANS_SERIF 兜底
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    }
}
