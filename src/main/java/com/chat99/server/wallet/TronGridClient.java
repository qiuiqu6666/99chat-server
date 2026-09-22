package com.chat99.server.wallet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TronGridClient {

    private static final Logger log = LoggerFactory.getLogger(TronGridClient.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final long BLOCK_CACHE_MS = 3000L;
    private static final int MAX_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MS = 800L;
    private static final long MAX_BACKOFF_MS = 12_000L;

    private final WalletConfigService configService;
    private final TronGridRateLimiter rateLimiter;
    private final OkHttpClient http = new OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build();
    private final ObjectMapper json = new ObjectMapper();
    private volatile long cachedBlockNumber = 0L;
    private volatile long cachedBlockAtMs = 0L;
    private final AtomicInteger apiKeyIndex = new AtomicInteger();

    public TronGridClient(WalletConfigService configService, TronGridRateLimiter rateLimiter) {
        this.configService = configService;
        this.rateLimiter = rateLimiter;
    }

    public record Trc20Transfer(
        String txId,
        int logIndex,
        String from,
        String to,
        long amountMicro,
        long blockTimestamp,
        int confirmations) {}

    public record AccountBalances(long trxSun, long usdtMicro) {}

    /** Activated accounts appear in TronGrid /v1/accounts; unactivated ones return empty data. */
    public boolean isAccountActivated(String base58Address) {
        if (base58Address == null || base58Address.isBlank()) {
            return false;
        }
        HttpUrl url = HttpUrl.parse(configService.getTrongridBaseUrl() + "/v1/accounts/" + base58Address.trim());
        if (url == null) {
            return false;
        }
        JsonNode root = getJson(url.toString());
        return root != null && root.has("data") && root.get("data").isArray() && !root.get("data").isEmpty();
    }

    public Optional<AccountBalances> fetchAccountBalances(String base58Address) {
        if (base58Address == null || base58Address.isBlank()) {
            return Optional.empty();
        }
        HttpUrl url = HttpUrl.parse(configService.getTrongridBaseUrl() + "/v1/accounts/" + base58Address.trim());
        if (url == null) {
            return Optional.empty();
        }
        JsonNode root = getJson(url.toString());
        long trxSun = 0L;
        long usdtMicro = 0L;
        if (root != null && root.has("data") && root.get("data").isArray() && !root.get("data").isEmpty()) {
            JsonNode data = root.get("data").get(0);
            trxSun = data.path("balance").asLong(0L);
            usdtMicro = parseUsdtMicroFromAccount(data);
        }
        boolean accountMissing = root != null && root.has("data") && root.get("data").isArray()
            && root.get("data").isEmpty();
        if (accountMissing || root == null) {
            long onChainUsdt = fetchUsdtBalanceMicro(base58Address.trim());
            if (onChainUsdt > 0L) {
                usdtMicro = onChainUsdt;
            } else if (root == null) {
                return Optional.empty();
            }
        }
        return Optional.of(new AccountBalances(trxSun, usdtMicro));
    }

    /**
     * Available ENERGY on address = EnergyLimit - EnergyUsed (from wallet/getaccountresource).
     * Returns 0 when account missing or API fails.
     */
    public long fetchAvailableEnergy(String base58Address) {
        if (base58Address == null || base58Address.isBlank()) {
            return 0L;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("address", base58Address.trim());
        body.put("visible", true);
        JsonNode root = postJson("/wallet/getaccountresource", body);
        if (root == null) {
            return 0L;
        }
        long limit = root.path("EnergyLimit").asLong(0L);
        long used = root.path("EnergyUsed").asLong(0L);
        return Math.max(0L, limit - used);
    }

    private long parseUsdtMicroFromAccount(JsonNode data) {
        String usdtContract = configService.getUsdtContract();
        JsonNode trc20 = data.get("trc20");
        if (trc20 == null || !trc20.isArray()) {
            return 0L;
        }
        for (JsonNode token : trc20) {
            if (token.has(usdtContract)) {
                try {
                    return Long.parseLong(token.get(usdtContract).asText("0"));
                } catch (NumberFormatException ignored) {
                    return 0L;
                }
            }
        }
        return 0L;
    }

    /** Calls USDT contract balanceOf for addresses not returned by /v1/accounts (unactivated). */
    private long fetchUsdtBalanceMicro(String base58Address) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("owner_address", base58Address);
        body.put("contract_address", configService.getUsdtContract());
        body.put("function_selector", "balanceOf(address)");
        body.put("parameter", buildAddressParameter(base58Address));
        body.put("visible", true);
        JsonNode result = postJson("/wallet/triggerconstantcontract", body);
        if (result == null) {
            return 0L;
        }
        JsonNode constant = result.get("constant_result");
        if (constant == null || !constant.isArray() || constant.isEmpty()) {
            return 0L;
        }
        try {
            String hex = constant.get(0).asText("0");
            if (hex.startsWith("0x")) {
                hex = hex.substring(2);
            }
            if (hex.isBlank()) {
                return 0L;
            }
            BigInteger value = new BigInteger(hex, 16);
            if (value.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
                return Long.MAX_VALUE;
            }
            return value.longValue();
        } catch (NumberFormatException e) {
            log.warn("parse USDT balanceOf hex failed addr={} err={}", base58Address, e.getMessage());
            return 0L;
        }
    }

    private String buildAddressParameter(String base58Address) {
        byte[] addrBytes = TronAddressUtils.decodeBase58ToBytes(base58Address);
        String addrHex = org.bouncycastle.util.encoders.Hex.toHexString(addrBytes);
        return String.format("%64s", addrHex).replace(' ', '0');
    }

    public String broadcastTrxTransfer(String fromPrivateKeyHex, String toBase58, long amountSun) throws IOException {
        if (amountSun <= 0) {
            throw new IOException("amount must be positive");
        }
        String fromAddress = TronTransactionSigner.privateKeyToBase58Address(fromPrivateKeyHex);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("owner_address", fromAddress);
        body.put("to_address", toBase58);
        body.put("amount", amountSun);
        body.put("visible", true);
        JsonNode tx = requirePostJson("/wallet/createtransaction", body, "创建 TRX 转账");
        if (tx.has("Error")) {
            throw new IOException("create transaction error: " + tx.get("Error").asText());
        }
        String rawHex = tx.path("raw_data_hex").asText(null);
        if (rawHex == null || rawHex.isBlank()) {
            throw new IOException("missing raw_data_hex");
        }
        String sig = TronTransactionSigner.signRawDataHex(rawHex, fromPrivateKeyHex);
        Map<String, Object> signed = json.convertValue(tx, Map.class);
        signed.put("signature", List.of(sig));
        JsonNode broadcast = requirePostJson("/wallet/broadcasttransaction", signed, "广播 TRX 转账");
        if (!broadcast.path("result").asBoolean(false)) {
            throw new IOException("broadcast rejected: " + broadcast);
        }
        return broadcast.path("txid").asText(tx.path("txID").asText(""));
    }

    public List<Trc20Transfer> fetchRecentUsdtTransfersTo(String toAddress, long minTimestampMs) {
        HttpUrl url = HttpUrl.parse(configService.getTrongridBaseUrl() + "/v1/accounts/" + toAddress + "/transactions/trc20")
            .newBuilder()
            .addQueryParameter("only_to", "true")
            .addQueryParameter("contract_address", configService.getUsdtContract())
            .addQueryParameter("limit", "50")
            .addQueryParameter("min_timestamp", String.valueOf(minTimestampMs))
            .addQueryParameter("order_by", "block_timestamp,asc")
            .build();
        JsonNode root = getJson(url.toString());
        if (root == null || !root.has("data")) {
            return List.of();
        }
        List<Trc20Transfer> out = new ArrayList<>();
        long nowBlock = getCurrentBlockNumberCached();
        for (JsonNode item : root.get("data")) {
            String txId = text(item, "transaction_id");
            String to = text(item, "to");
            if (txId == null || to == null || !to.equalsIgnoreCase(toAddress)) {
                continue;
            }
            String value = text(item, "value");
            if (value == null) continue;
            long amount = Long.parseLong(value);
            long ts = item.path("block_timestamp").asLong(0);
            int conf = estimateConfirmations(item, nowBlock);
            out.add(new Trc20Transfer(txId, 0, text(item, "from"), to, amount, ts, conf));
        }
        return out;
    }

    public Optional<Trc20Transfer> fetchUsdtTransferByTxId(String txId, String expectedToAddress) {
        if (txId == null || txId.isBlank() || expectedToAddress == null || expectedToAddress.isBlank()) {
            return Optional.empty();
        }
        HttpUrl url = HttpUrl.parse(configService.getTrongridBaseUrl() + "/v1/transactions/" + txId + "/events")
            .newBuilder()
            .addQueryParameter("limit", "50")
            .build();
        JsonNode root = getJson(url.toString());
        if (root == null || !root.has("data")) {
            return Optional.empty();
        }
        long nowBlock = getCurrentBlockNumberCached();
        int logIndex = 0;
        for (JsonNode item : root.get("data")) {
            Optional<Trc20Transfer> parsed = parseUsdtTransferEvent(item, nowBlock, logIndex);
            if (parsed.isPresent() && expectedToAddress.equalsIgnoreCase(parsed.get().to())) {
                return Optional.of(new Trc20Transfer(
                    txId, parsed.get().logIndex(), parsed.get().from(), parsed.get().to(),
                    parsed.get().amountMicro(), parsed.get().blockTimestamp(), parsed.get().confirmations()));
            }
            logIndex++;
        }
        return Optional.empty();
    }

    public Optional<List<Trc20Transfer>> fetchUsdtTransfersInBlock(long blockNumber) {
        HttpUrl url = HttpUrl.parse(configService.getTrongridBaseUrl() + "/v1/blocks/" + blockNumber + "/events")
            .newBuilder()
            .addQueryParameter("limit", "200")
            .build();
        JsonNode root = getJson(url.toString());
        if (root == null) {
            return Optional.empty();
        }
        if (!root.has("data")) {
            return Optional.of(List.of());
        }
        long nowBlock = getCurrentBlockNumberCached();
        List<Trc20Transfer> out = new ArrayList<>();
        int logIndex = 0;
        for (JsonNode item : root.get("data")) {
            parseUsdtTransferEvent(item, nowBlock, logIndex).ifPresent(out::add);
            logIndex++;
        }
        return Optional.of(out);
    }

    public long getCurrentBlockNumberCached() {
        long now = System.currentTimeMillis();
        if (cachedBlockNumber > 0 && now - cachedBlockAtMs < BLOCK_CACHE_MS) {
            return cachedBlockNumber;
        }
        long block = currentBlockNumber();
        if (block > 0) {
            cachedBlockNumber = block;
            cachedBlockAtMs = now;
        }
        return block;
    }

    public int getTransactionConfirmations(String txId) {
        HttpUrl url = HttpUrl.parse(configService.getTrongridBaseUrl() + "/v1/transactions/" + txId + "/info");
        JsonNode root = getJson(url.toString());
        if (root != null && root.has("confirmed") && root.get("confirmed").asBoolean()) {
            long block = root.path("blockNumber").asLong(0);
            if (block > 0) {
                return confirmationsFromBlock(block);
            }
            return 1;
        }
        long block = blockNumberFromEvents(txId);
        return block > 0 ? confirmationsFromBlock(block) : 0;
    }

    private long blockNumberFromEvents(String txId) {
        HttpUrl url = HttpUrl.parse(configService.getTrongridBaseUrl() + "/v1/transactions/" + txId + "/events")
            .newBuilder()
            .addQueryParameter("limit", "50")
            .build();
        JsonNode root = getJson(url.toString());
        if (root == null || !root.has("data")) {
            return 0L;
        }
        long maxBlock = 0L;
        for (JsonNode item : root.get("data")) {
            long block = item.path("block_number").asLong(0);
            if (block > maxBlock) {
                maxBlock = block;
            }
        }
        return maxBlock;
    }

    private int confirmationsFromBlock(long block) {
        long current = getCurrentBlockNumberCached();
        if (block > 0 && current >= block) {
            return (int) Math.min(Integer.MAX_VALUE, current - block + 1);
        }
        return 0;
    }

    public String broadcastUsdtTransfer(String fromPrivateKeyHex, String toBase58, long amountMicro) throws IOException {
        String fromAddress = TronTransactionSigner.privateKeyToBase58Address(fromPrivateKeyHex);
        String param = buildTransferParameter(toBase58, amountMicro);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("owner_address", fromAddress);
        body.put("contract_address", configService.getUsdtContract());
        body.put("function_selector", "transfer(address,uint256)");
        body.put("parameter", param);
        body.put("fee_limit", 100_000_000L);
        body.put("visible", true);
        JsonNode trigger = requirePostJson("/wallet/triggersmartcontract", body, "创建 USDT 转账");
        if (trigger.has("result") && trigger.get("result").has("code")
            && !"SUCCESS".equalsIgnoreCase(trigger.get("result").path("message").asText(""))) {
            String msg = trigger.get("result").toString();
            if (trigger.has("Error")) {
                msg = trigger.get("Error").asText(msg);
            }
            throw new IOException("trigger error: " + msg);
        }
        JsonNode tx = trigger.get("transaction");
        if (tx == null) {
            throw new IOException("missing transaction in trigger response");
        }
        String rawHex = tx.path("raw_data_hex").asText(null);
        if (rawHex == null || rawHex.isBlank()) {
            throw new IOException("missing raw_data_hex");
        }
        String sig = TronTransactionSigner.signRawDataHex(rawHex, fromPrivateKeyHex);
        Map<String, Object> signed = json.convertValue(tx, Map.class);
        signed.put("signature", List.of(sig));
        JsonNode broadcast = requirePostJson("/wallet/broadcasttransaction", signed, "广播 USDT 转账");
        if (!broadcast.path("result").asBoolean(false)) {
            throw new IOException("broadcast rejected: " + broadcast);
        }
        return broadcast.path("txid").asText(tx.path("txID").asText(""));
    }

    private Optional<Trc20Transfer> parseUsdtTransferEvent(JsonNode item, long currentBlock, int logIndex) {
        String contract = text(item, "contract_address");
        if (contract == null || !contract.equalsIgnoreCase(configService.getUsdtContract())) {
            return Optional.empty();
        }
        String eventName = text(item, "event_name");
        if (eventName != null && !eventName.equalsIgnoreCase("Transfer")) {
            return Optional.empty();
        }
        JsonNode result = item.get("result");
        if (result == null || !result.isObject()) {
            return Optional.empty();
        }
        String to = firstText(result, "to", "1");
        String from = firstText(result, "from", "0");
        String value = firstText(result, "value", "2");
        if (to == null || value == null) {
            return Optional.empty();
        }
        long amount;
        try {
            amount = Long.parseLong(value);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        String txId = text(item, "transaction_id");
        if (txId == null) {
            return Optional.empty();
        }
        long ts = item.path("block_timestamp").asLong(0);
        long block = item.path("block_number").asLong(item.path("block").asLong(0));
        int conf = block > 0 && currentBlock >= block
            ? (int) Math.min(Integer.MAX_VALUE, currentBlock - block + 1)
            : (item.path("confirmed").asBoolean(false) ? 1 : 0);
        return Optional.of(new Trc20Transfer(txId, logIndex, from, to, amount, ts, conf));
    }

    private String buildTransferParameter(String toBase58, long amountMicro) {
        byte[] addrBytes = TronAddressUtils.decodeBase58ToBytes(toBase58);
        String addrHex = org.bouncycastle.util.encoders.Hex.toHexString(addrBytes);
        String addrParam = String.format("%64s", addrHex).replace(' ', '0');
        String amountHex = String.format("%64s", Long.toHexString(amountMicro)).replace(' ', '0');
        return addrParam + amountHex;
    }

    private int estimateConfirmations(JsonNode item, long currentBlock) {
        long block = item.path("block").asLong(0);
        if (block > 0 && currentBlock >= block) {
            return (int) Math.min(Integer.MAX_VALUE, currentBlock - block + 1);
        }
        return item.path("confirmed").asBoolean(false) ? 1 : 0;
    }

    private long currentBlockNumber() {
        JsonNode node = postJson("/wallet/getnowblock", Map.of());
        if (node == null) return 0;
        return node.path("block_header").path("raw_data").path("number").asLong(0);
    }

    private JsonNode requirePostJson(String path, Object body, String operation) throws IOException {
        JsonNode node = postJson(path, body);
        if (node == null) {
            throw new IOException("TronGrid " + operation + " 失败：API 限流或暂时不可用(429)，请稍后重试");
        }
        return node;
    }

    private JsonNode getJson(String url) {
        long backoffMs = INITIAL_BACKOFF_MS;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            rateLimiter.acquire();
            Request.Builder rb = new Request.Builder().url(url).get();
            addApiKey(rb);
            try (Response resp = http.newCall(rb.build()).execute()) {
                if (resp.code() == 429) {
                    long retryMs = retryAfterMillis(resp, backoffMs);
                    rateLimiter.coolDownMillis(retryMs);
                    log.warn("TronGrid GET {} -> 429 rate limited (attempt {}/{}) retryInMs={}",
                        url, attempt, MAX_ATTEMPTS, retryMs);
                    if (attempt < MAX_ATTEMPTS) {
                        sleepQuietly(retryMs);
                        backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
                        continue;
                    }
                    return null;
                }
                if (!resp.isSuccessful() || resp.body() == null) {
                    log.warn("TronGrid GET {} -> {}", url, resp.code());
                    return null;
                }
                return json.readTree(resp.body().string());
            } catch (Exception e) {
                log.warn("TronGrid GET {} err={}", url, e.getMessage());
                return null;
            }
        }
        return null;
    }

    private JsonNode postJson(String path, Object body) {
        long backoffMs = INITIAL_BACKOFF_MS;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            rateLimiter.acquire();
            try {
                String payload = json.writeValueAsString(body);
                Request.Builder rb = new Request.Builder()
                    .url(configService.getTrongridBaseUrl() + path)
                    .post(RequestBody.create(payload, JSON));
                addApiKey(rb);
                try (Response resp = http.newCall(rb.build()).execute()) {
                    if (resp.code() == 429) {
                        long retryMs = retryAfterMillis(resp, backoffMs);
                        rateLimiter.coolDownMillis(retryMs);
                        log.warn("TronGrid POST {} -> 429 rate limited (attempt {}/{}) retryInMs={}",
                            path, attempt, MAX_ATTEMPTS, retryMs);
                        if (attempt < MAX_ATTEMPTS) {
                            sleepQuietly(retryMs);
                            backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
                            continue;
                        }
                        return null;
                    }
                    if (!resp.isSuccessful() || resp.body() == null) {
                        log.warn("TronGrid POST {} -> {}", path, resp.code());
                        return null;
                    }
                    return json.readTree(resp.body().string());
                }
            } catch (Exception e) {
                log.warn("TronGrid POST {} err={}", path, e.getMessage());
                return null;
            }
        }
        return null;
    }

    private static long retryAfterMillis(Response response, long fallback) {
        String retryAfter = response.header("Retry-After");
        if (retryAfter != null) {
            try {
                return Math.min(MAX_BACKOFF_MS, Math.max(1_000L, Long.parseLong(retryAfter.trim()) * 1_000L));
            } catch (NumberFormatException ignored) {
                try {
                    long delay = Duration.between(java.time.Instant.now(),
                        java.time.ZonedDateTime.parse(retryAfter).toInstant()).toMillis();
                    return Math.min(MAX_BACKOFF_MS, Math.max(1_000L, delay));
                } catch (Exception ignoredDate) {
                    // Use exponential fallback when the header is malformed.
                }
            }
        }
        return Math.min(MAX_BACKOFF_MS, Math.max(1_000L, fallback));
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void addApiKey(Request.Builder rb) {
        java.util.List<String> keys = configService.getTrongridApiKeys();
        if (!keys.isEmpty()) {
            String apiKey = keys.get(Math.floorMod(apiKeyIndex.getAndIncrement(), keys.size()));
            rb.header("TRON-PRO-API-KEY", apiKey);
        }
    }

    private static String text(JsonNode n, String field) {
        if (n == null || !n.has(field) || n.get(field).isNull()) return null;
        return n.get(field).asText();
    }

    private static String firstText(JsonNode result, String... fields) {
        for (String field : fields) {
            String v = text(result, field);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
