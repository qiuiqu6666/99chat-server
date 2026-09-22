package com.chat99.sangong.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 开彩金额解析：90→90、0.88→88、1.00/1→100；与 PHP DrawAmountParser 一致。 */
@Service
public class DrawAmountParser {
    private static final Pattern TWO_DIGITS = Pattern.compile("^\\d{2}$");
    private static final Pattern DECIMAL = Pattern.compile("^0\\.(\\d{1,2})$");

    public int parse(String rawInput) {
        String raw = rawInput == null ? "" : rawInput.replace(" ", "").trim();
        if (raw.isEmpty()) {
            throw new RuntimeException("开彩金额不能为空");
        }
        if (TWO_DIGITS.matcher(raw).matches()) {
            if (raw.equals("00")) {
                return 100;
            }
            return Integer.parseInt(raw);
        }
        if (raw.equals("1") || raw.equals("1.0") || raw.equals("1.00")) {
            return 100;
        }
        if (raw.equals("0.00") || raw.equals("0") || raw.equals("0.0")) {
            throw new RuntimeException("不允许录入0.00");
        }
        Matcher m = DECIMAL.matcher(raw);
        if (m.matches()) {
            String fraction = m.group(1);
            if (fraction.length() == 1) {
                fraction = fraction + "0";
            }
            int value = Integer.parseInt(fraction);
            if (value < 1 || value > 99) {
                throw new RuntimeException("开彩金额须在0.01～0.99或1.00之间");
            }
            return value;
        }
        throw new RuntimeException("开彩金额格式无效，请使用如90、0.88、1.00");
    }

    public String format(int hundredths) {
        if (hundredths == 100) {
            return "1.00";
        }
        if (hundredths < 1 || hundredths > 99) {
            throw new RuntimeException("开彩金额无效");
        }
        return String.format("0.%02d", hundredths);
    }
}
