package com.chat99.server.group;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 将用户输入的 keyword 解析为候选 IM groupId（按优先级排序、去重）。
 * 切流后普通群为 {@code m…}、社群为 {@code @TGS#_m…}，禁止再把 {@code m…} 套成 {@code @TGS#_@TGS#m…}。
 */
public final class GroupJoinKeywordResolver {

    private static final String COMMUNITY_PREFIX = "@TGS#_@TGS#";
    private static final String TGS_PREFIX = "@TGS#";
    private static final Pattern CUSTOM_M_ID = Pattern.compile("^m[A-Za-z0-9_-]+$");
    private static final Pattern MIGRATED_COMMUNITY_ID = Pattern.compile("^@TGS#_m[A-Za-z0-9_-]+$");

    private GroupJoinKeywordResolver() {
    }

    /** 将用户输入的 keyword 解析为候选 IM groupId（按优先级排序、去重）。 */
    public static List<String> candidateGroupIds(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String k = keyword.trim();
        Set<String> out = new LinkedHashSet<>();

        if (CUSTOM_M_ID.matcher(k).matches()) {
            out.add(k);
            return List.copyOf(out);
        }
        if (MIGRATED_COMMUNITY_ID.matcher(k).matches()) {
            out.add(k);
            return List.copyOf(out);
        }

        if (k.startsWith(COMMUNITY_PREFIX)) {
            String suffix = k.substring(COMMUNITY_PREFIX.length()).trim();
            if (CUSTOM_M_ID.matcher(suffix).matches()) {
                out.add(suffix);
            }
            out.add(k);
            return List.copyOf(out);
        }

        if (k.startsWith(TGS_PREFIX)) {
            String suffix = k.substring(TGS_PREFIX.length()).trim();
            if (!suffix.isEmpty()) {
                if (CUSTOM_M_ID.matcher(suffix).matches()) {
                    out.add(suffix);
                } else {
                    out.add("m" + suffix);
                }
                out.add(k);
                if (!suffix.startsWith("m")) {
                    out.add(COMMUNITY_PREFIX + suffix);
                }
            } else {
                out.add(k);
            }
            return List.copyOf(out);
        }

        if (k.startsWith("@")) {
            addBareCandidates(out, k.substring(1).trim());
            return List.copyOf(out);
        }

        addBareCandidates(out, k);
        return List.copyOf(out);
    }

    private static void addBareCandidates(Set<String> out, String bare) {
        if (bare == null || bare.isEmpty()) {
            return;
        }
        if (CUSTOM_M_ID.matcher(bare).matches()) {
            out.add(bare);
            return;
        }
        out.add("m" + bare);
        out.add(TGS_PREFIX + bare);
        if (!bare.startsWith("m")) {
            out.add(COMMUNITY_PREFIX + bare);
        }
    }
}
