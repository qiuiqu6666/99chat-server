package com.chat99.server.user;

/** Minimal geohash encoder for coarse location indexing (no reverse-geocode). */
final class GeoHash {

    private static final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";

    private GeoHash() {}

    static String encode(double lat, double lng, int precision) {
        if (precision <= 0) {
            precision = 5;
        }
        if (precision > 12) {
            precision = 12;
        }
        double[] latRange = {-90.0, 90.0};
        double[] lngRange = {-180.0, 180.0};
        StringBuilder hash = new StringBuilder(precision);
        boolean even = true;
        int bit = 0;
        int ch = 0;
        while (hash.length() < precision) {
            if (even) {
                double mid = (lngRange[0] + lngRange[1]) / 2;
                if (lng >= mid) {
                    ch |= 1 << (4 - bit);
                    lngRange[0] = mid;
                } else {
                    lngRange[1] = mid;
                }
            } else {
                double mid = (latRange[0] + latRange[1]) / 2;
                if (lat >= mid) {
                    ch |= 1 << (4 - bit);
                    latRange[0] = mid;
                } else {
                    latRange[1] = mid;
                }
            }
            even = !even;
            if (bit < 4) {
                bit++;
            } else {
                hash.append(BASE32.charAt(ch));
                bit = 0;
                ch = 0;
            }
        }
        return hash.toString();
    }
}
