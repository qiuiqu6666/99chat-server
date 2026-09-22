package com.chat99.sangong.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 指令文本规范化与金额解析（对应 PHP GameCommandTextHelper）。 */
public final class GameCommandTextHelper {

    /** 门号与金额分隔符：。 . , ， / 、 + : ： ; ； ( （ ) ） ! ！ ? ？ - 或空白 */
    public static final String SEPARATOR = "(?:[。.,，/、+:：;；()（）!！?？\\-]|\\s+)";

    private static final Pattern QUAN_PREFIX = Pattern.compile("^quan", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAIRED_PARENS = Pattern.compile("^(\\d+|全)\\((\\d+)\\)$");
    private static final Pattern WAN_AMOUNT = Pattern.compile("^(\\d+)万$");
    private static final Pattern PLAIN_AMOUNT = Pattern.compile("^\\d+$");

    private GameCommandTextHelper() {}

    public static String normalizeCommandText(String input) {
        String text = input == null ? "" : input.trim();
        if (text.isEmpty()) {
            return "";
        }
        text = toHalfWidth(text).trim();
        if (QUAN_PREFIX.matcher(text).find()) {
            text = "全" + text.substring(4);
        }
        Matcher m = PAIRED_PARENS.matcher(text);
        if (m.matches()) {
            text = m.group(1) + "(" + m.group(2);
        }
        return text;
    }

    /** 全角字母数字符号与空格转半角（对应 mb_convert_kana 'as'）。 */
    static String toHalfWidth(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u3000') {
                sb.append(' ');
            } else if (c >= '\uFF01' && c <= '\uFF5E') {
                sb.append((char) (c - 0xFEE0));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 金额片段：纯数字或带「万」后缀（5万 → 50000）。 */
    public static Integer parseAmount(String amountPart) {
        String s = amountPart == null ? "" : amountPart.trim();
        if (s.isEmpty()) {
            return null;
        }
        Matcher wan = WAN_AMOUNT.matcher(s);
        if (wan.matches()) {
            int v = Integer.parseInt(wan.group(1));
            if (v <= 0) {
                return null;
            }
            return v * 10000;
        }
        if (PLAIN_AMOUNT.matcher(s).matches()) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
