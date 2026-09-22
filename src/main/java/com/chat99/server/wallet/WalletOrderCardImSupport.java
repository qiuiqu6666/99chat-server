package com.chat99.server.wallet;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 从腾讯云 IM 回调 MsgBody 中解析钱包自定义卡片（wallet_order）。
 */
public final class WalletOrderCardImSupport {

    public static final String BUSINESS_ID = "wallet_order";
    public static final String TYPE_TRANSFER = "wallet_transfer";
    public static final String TYPE_RED_PACKET = "wallet_red_packet";
    public static final String TYPE_GROUP_TRANSFER = "wallet_group_transfer";

    private static final Set<String> CARD_TYPES = Set.of(
        TYPE_TRANSFER, TYPE_RED_PACKET, TYPE_GROUP_TRANSFER);

    private WalletOrderCardImSupport() {}

    public record WalletCard(
        String customType,
        String orderIdRaw,
        String publicId,
        String clientOrderId,
        String currency,
        Long amount,
        String senderUserId,
        String fromUserId,
        String toUserId,
        String groupId) {}

    public static List<WalletCard> extractCards(Map<String, Object> body, ObjectMapper json) {
        if (body == null || body.isEmpty() || json == null) {
            return List.of();
        }
        Object msgBodyRaw = body.get("MsgBody");
        if (!(msgBodyRaw instanceof List<?> msgBody) || msgBody.isEmpty()) {
            return List.of();
        }
        List<WalletCard> out = new ArrayList<>();
        for (Object item : msgBody) {
            if (!(item instanceof Map<?, ?> msg)) {
                continue;
            }
            if (!"TIMCustomElem".equals(str(msg.get("MsgType")))) {
                continue;
            }
            Object contentRaw = msg.get("MsgContent");
            if (!(contentRaw instanceof Map<?, ?> content)) {
                continue;
            }
            Map<String, Object> data = parseDataMap(str(content.get("Data")), json);
            if (data == null || data.isEmpty()) {
                continue;
            }
            if (!isWalletOrderCard(data)) {
                continue;
            }
            out.add(toCard(data));
        }
        return out;
    }

    static boolean isWalletOrderCard(Map<String, Object> data) {
        String businessId = firstNonBlank(
            str(data.get("businessID")), str(data.get("businessId")));
        if (BUSINESS_ID.equalsIgnoreCase(businessId)) {
            return true;
        }
        String type = normalizeType(data);
        return type != null && CARD_TYPES.contains(type);
    }

    static String normalizeType(Map<String, Object> data) {
        String raw = firstNonBlank(str(data.get("customType")), str(data.get("type")));
        if (raw == null) {
            return null;
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static WalletCard toCard(Map<String, Object> data) {
        String type = normalizeType(data);
        if (type == null && BUSINESS_ID.equalsIgnoreCase(
            firstNonBlank(str(data.get("businessID")), str(data.get("businessId"))))) {
            // 仅有 businessID 时按红包兜底分类，后续用 order 表判定
            type = TYPE_RED_PACKET;
        }
        return new WalletCard(
            type,
            firstNonBlank(str(data.get("orderId")), str(data.get("order_id"))),
            firstNonBlank(str(data.get("publicId")), str(data.get("public_id"))),
            firstNonBlank(
                firstNonBlank(str(data.get("clientOrderId")), str(data.get("client_order_id"))),
                firstNonBlank(str(data.get("publicId")), str(data.get("public_id")))),
            str(data.get("currency")),
            parseLong(data.get("amount")),
            firstNonBlank(str(data.get("senderUserId")), str(data.get("sender_user_id"))),
            firstNonBlank(str(data.get("fromUserId")), str(data.get("from_user_id"))),
            firstNonBlank(str(data.get("toUserId")), str(data.get("to_user_id"))),
            firstNonBlank(str(data.get("groupId")), str(data.get("group_id"))));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseDataMap(String dataJson, ObjectMapper json) {
        if (dataJson == null || dataJson.isBlank()) {
            return null;
        }
        String trimmed = dataJson.trim();
        try {
            Object parsed = json.readValue(trimmed, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            if (parsed instanceof String nested && !nested.isBlank()) {
                return json.readValue(nested, new TypeReference<>() {});
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    private static Long parseLong(Object raw) {
        if (raw instanceof Number n) {
            return n.longValue();
        }
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
