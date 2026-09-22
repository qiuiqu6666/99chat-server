package com.chat99.sangong.image;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.util.List;

/**
 * 报表表格边线（与 PHP ImageBorderHelper 对齐）：
 * 灰绿色 #62854F 细边线，线宽随清晰度倍率缩放。
 */
public final class ImageBorder {
    public static final Color COLOR = new Color(98, 133, 79);

    /** 统计清单在 scale=3 时的数据行高（21 逻辑 px）。 */
    private static final int REFERENCE_ROW_LOGICAL_PX = 21;

    private ImageBorder() {}

    public static int baseWidth(int scale) {
        return scale;
    }

    /** 按行高折算线宽，保证视觉粗细与统计清单一致。 */
    public static int widthForRowHeight(int scale, int rowHeightPx) {
        int reference = REFERENCE_ROW_LOGICAL_PX * scale;
        int base = baseWidth(scale);
        if (reference <= 0 || rowHeightPx >= reference) {
            return base;
        }
        return Math.max(1, (int) Math.round((double) base * rowHeightPx / reference));
    }

    /** 外框 + 各列竖线（与 PHP drawRowGrid 一致）。 */
    public static void drawRowGrid(Graphics2D g, int scale, int x, int y, List<Integer> cols, int h, Color color) {
        if (cols.isEmpty()) {
            return;
        }
        int rowWidth = cols.get(cols.size() - 1);
        int lineWidth = widthForRowHeight(scale, h);
        rectangle(g, x, y, x + rowWidth, y + h, color, lineWidth);
        for (int offset : cols) {
            line(g, x + offset, y, x + offset, y + h, color, lineWidth);
        }
    }

    public static void rectangle(Graphics2D g, int x1, int y1, int x2, int y2, Color color, int width) {
        Stroke old = g.getStroke();
        g.setStroke(new BasicStroke(width));
        g.setColor(color);
        g.drawRect(Math.min(x1, x2), Math.min(y1, y2), Math.abs(x2 - x1), Math.abs(y2 - y1));
        g.setStroke(old);
    }

    public static void line(Graphics2D g, int x1, int y1, int x2, int y2, Color color, int width) {
        Stroke old = g.getStroke();
        g.setStroke(new BasicStroke(width));
        g.setColor(color);
        g.drawLine(x1, y1, x2, y2);
        g.setStroke(old);
    }
}
