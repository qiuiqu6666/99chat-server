package com.chat99.server.im;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 业务 user_id 与腾讯 IM UserID 恒等（无映射表）。
 */
@Service
public class ImUserIdService {

    public static final Set<String> SPECIAL_IM_ACCOUNTS = Set.of(
        "administrator",
        "99Messenger",
        "99Chat"
    );

    public boolean isSpecialImAccount(String id) {
        return id != null && SPECIAL_IM_ACCOUNTS.contains(id.trim());
    }

    /** 业务 → IM：恒等。 */
    public String toIm(String businessUserId) {
        return trimOrSame(businessUserId);
    }

    /** 归档/回调侧账号归一：恒等。 */
    public String toImAccount(String businessOrImUserId) {
        return trimOrSame(businessOrImUserId);
    }

    /** IM → 业务：恒等。 */
    public String toBusiness(String imUserId) {
        return trimOrSame(imUserId);
    }

    public Optional<String> findImUserId(String businessUserId) {
        if (businessUserId == null || businessUserId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(businessUserId.trim());
    }

    public Optional<String> findBusinessUserId(String imUserId) {
        if (imUserId == null || imUserId.isBlank()) {
            return Optional.empty();
        }
        String id = imUserId.trim();
        if (isSpecialImAccount(id)) {
            return Optional.empty();
        }
        return Optional.of(id);
    }

    public String requireImUserId(String businessUserId) {
        if (businessUserId == null || businessUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_USER_ID");
        }
        String id = businessUserId.trim();
        if (isSpecialImAccount(id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SPECIAL_ACCOUNT_NOT_MAPPED");
        }
        return id;
    }

    public String requireBusinessUserId(String imUserId) {
        if (imUserId == null || imUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_IM_USER_ID");
        }
        String id = imUserId.trim();
        if (isSpecialImAccount(id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SPECIAL_ACCOUNT_NOT_MAPPED");
        }
        return id;
    }

    /** UserSig：返回业务号。 */
    public String resolveImUserIdForSign(String businessUserId) {
        if (businessUserId == null || businessUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_USER_ID");
        }
        return businessUserId.trim();
    }

    /** 展示用：恒等。 */
    public String toBusinessForDisplay(String imOrAccount) {
        return trimOrSame(imOrAccount);
    }

    public Map<String, String> toBusinessForDisplayBatch(Collection<String> imAccounts) {
        Map<String, String> out = new HashMap<>();
        if (imAccounts == null || imAccounts.isEmpty()) {
            return out;
        }
        for (String raw : imAccounts) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String id = raw.trim();
            out.put(id, id);
        }
        return out;
    }

    public Map<String, String> toImBatch(Collection<String> businessUserIds) {
        Map<String, String> out = new HashMap<>();
        if (businessUserIds == null || businessUserIds.isEmpty()) {
            return out;
        }
        for (String raw : businessUserIds) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String id = raw.trim();
            out.put(id, id);
        }
        return out;
    }

    private static String trimOrSame(String id) {
        if (id == null || id.isBlank()) {
            return id;
        }
        return id.trim();
    }
}
