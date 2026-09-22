package com.chat99.server.robot;

import java.security.SecureRandom;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Machine codes:
 * <ul>
 *   <li>New: {@code XXXX-XXXX-XXXX} (12 Crockford Base32 chars)</li>
 *   <li>Legacy seed: existing {@code player_group_id} values (e.g. {@code @2HGQG6M5CD})</li>
 * </ul>
 */
public final class MachineCodeSupport {

    public static final String HEADER_MACHINE_CODE = "X-Machine-Code";
    public static final String HEADER_GROUP_ID = "X-Group-Id";

    private static final String CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_CODE_LEN = 64;

    private MachineCodeSupport() {}

    public static String generate() {
        char[] raw = new char[12];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = CROCKFORD.charAt(RANDOM.nextInt(CROCKFORD.length()));
        }
        return format(new String(raw));
    }

    /**
     * Canonical form for DB lookup / storage.
     * New-format codes become {@code XXXX-XXXX-XXXX}; legacy opaque ids are trimmed as-is.
     */
    public static String canonicalize(String input) {
        if (input == null || input.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing machine code");
        }
        String trimmed = input.trim();
        if (trimmed.length() > MAX_CODE_LEN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid machine code");
        }
        String compact = trimmed.toUpperCase(Locale.ROOT).replace("-", "");
        if (compact.length() == 12 && isCrockford(compact)) {
            return format(compact);
        }
        // Legacy / opaque tenant keys (seeded from player_group_id)
        return trimmed;
    }

    public static String mask(String machineCode) {
        String code = canonicalize(machineCode);
        if (code.length() <= 8) {
            return "****";
        }
        return code.substring(0, Math.min(4, code.length()))
            + "****"
            + code.substring(code.length() - Math.min(4, code.length() - 4));
    }

    public static String requireGroupId(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GROUP_ID_REQUIRED");
        }
        return groupId.trim();
    }

    private static boolean isCrockford(String compact) {
        for (int i = 0; i < compact.length(); i++) {
            if (CROCKFORD.indexOf(compact.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    private static String format(String compact12) {
        return compact12.substring(0, 4) + "-" + compact12.substring(4, 8) + "-" + compact12.substring(8, 12);
    }
}
