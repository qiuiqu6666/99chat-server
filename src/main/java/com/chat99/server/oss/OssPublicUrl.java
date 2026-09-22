package com.chat99.server.oss;

/**
 * 公开 OSS URL 规范化。群 ID 含 {@code #}（如 {@code @TGS#xxx}）时，未编码的 {@code #} 会被 HTTP 客户端当作 fragment，导致图片 404。
 */
public final class OssPublicUrl {

    private OssPublicUrl() {
    }

    /** 编码 objectKey 中需出现在 URL path 的特殊字符。 */
    public static String encodeObjectKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return objectKey;
        }
        return objectKey.replace("#", "%23");
    }

    /** 修正已存储/下发的公开 URL（兼容历史未编码数据）。 */
    public static String normalizePublicUrl(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return url;
        }
        int pathStart = url.indexOf('/', schemeEnd + 3);
        if (pathStart < 0 || !url.contains("#")) {
            return url;
        }
        String prefix = url.substring(0, pathStart + 1);
        String path = url.substring(pathStart + 1);
        if (!path.contains("#")) {
            return url;
        }
        return prefix + path.replace("#", "%23");
    }
}
