package com.chat99.server.security;

import com.chat99.server.user.User;
import com.chat99.server.user.UserRepository;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 登录态用户状态短缓存。会话是否有效仍每次查 Redis。
 */
@Service
public class UserAuthStatusService {

    static final long FOUND_TTL_MS = 60_000L;
    static final long ABSENT_TTL_MS = 10_000L;
    static final int MAX_ENTRIES = 100_000;

    private final UserRepository userRepository;
    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Result>> inflight = new ConcurrentHashMap<>();

    public UserAuthStatusService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Result resolve(String userId) {
        long now = System.currentTimeMillis();
        Entry hit = cache.get(userId);
        if (hit != null && hit.expiresAtMs > now) {
            return hit.result;
        }
        CompletableFuture<Result> created = new CompletableFuture<>();
        CompletableFuture<Result> existing = inflight.putIfAbsent(userId, created);
        if (existing != null) {
            return join(existing);
        }
        try {
            Entry again = cache.get(userId);
            if (again != null && again.expiresAtMs > System.currentTimeMillis()) {
                created.complete(again.result);
                return again.result;
            }
            Result loaded = load(userId);
            store(userId, loaded);
            created.complete(loaded);
            return loaded;
        } catch (RuntimeException e) {
            created.completeExceptionally(e);
            throw e;
        } finally {
            inflight.remove(userId, created);
        }
    }

    public void invalidate(String userId) {
        if (userId != null && !userId.isBlank()) {
            cache.remove(userId);
        }
    }

    private Result load(String userId) {
        Optional<User> user = userRepository.findByUserId(userId);
        if (user.isEmpty()) {
            return Result.absent();
        }
        return Result.found(user.get().getStatus());
    }

    private void store(String userId, Result result) {
        long now = System.currentTimeMillis();
        if (cache.size() >= MAX_ENTRIES && !cache.containsKey(userId)) {
            evictExpired(now);
            if (cache.size() >= MAX_ENTRIES) {
                return;
            }
        }
        long ttl = result.found() ? FOUND_TTL_MS : ABSENT_TTL_MS;
        cache.put(userId, new Entry(result, now + ttl));
    }

    private void evictExpired(long now) {
        Iterator<Map.Entry<String, Entry>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expiresAtMs <= now) {
                it.remove();
            }
        }
    }

    private static Result join(CompletableFuture<Result> existing) {
        try {
            return existing.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw e;
        }
    }

    public record Result(boolean found, int status) {
        public static Result absent() {
            return new Result(false, 0);
        }

        public static Result found(int status) {
            return new Result(true, status);
        }
    }

    private record Entry(Result result, long expiresAtMs) {}
}
