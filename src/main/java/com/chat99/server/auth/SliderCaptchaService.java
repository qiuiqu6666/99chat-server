/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.auth;

import com.chat99.server.auth.SliderCaptchaImageGenerator;
import com.chat99.server.auth.SliderCaptchaService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class SliderCaptchaService {
    private static final Logger log = LoggerFactory.getLogger(SliderCaptchaService.class);
    private final StringRedisTemplate redis;
    private final SliderCaptchaImageGenerator imageGenerator;
    private static final Duration TTL = Duration.ofMinutes(5L);
    private static final int MIN_X = 50;
    private static final int MAX_X = 220;
    private static final int MIN_Y = 30;
    private static final int MAX_Y = 120;
    private static final int GAP_WIDTH = 50;
    private static final int GAP_HEIGHT = 50;
    private static final int TOLERANCE_X = 5;
    private static final int TOLERANCE_Y = 5;
    private static final int FAKE_GAP_COUNT = 2;

    public SliderCaptchaService(StringRedisTemplate redis, SliderCaptchaImageGenerator imageGenerator) {
        this.redis = redis;
        this.imageGenerator = imageGenerator;
    }

    public SliderCaptchaInitResult initCaptcha() {
        String token = UUID.randomUUID().toString();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int targetX = rnd.nextInt(50, 221);
        int targetY = rnd.nextInt(30, 121);
        List<Map<String, Integer>> fakeGaps = this.generateFakeGaps(rnd, targetX, targetY);
        byte[] backgroundBytes = this.imageGenerator.generateBackground();
        String backgroundBase64 = Base64.getEncoder().encodeToString(backgroundBytes);
        String key = SliderCaptchaService.getKey(token);
        Map<String, String> payload = Map.of("targetX", Integer.toString(targetX), "targetY", Integer.toString(targetY), "createdAt", Long.toString(System.currentTimeMillis()));
        this.redis.opsForHash().putAll((Object)key, payload);
        this.redis.expire(key, TTL);
        log.debug("slider captcha initialized: token={} fakeGaps={}", (Object)token, (Object)fakeGaps.size());
        return new SliderCaptchaInitResult(token, targetX, targetY, targetX, targetY, 50, 50, fakeGaps, backgroundBase64);
    }

    private List<Map<String, Integer>> generateFakeGaps(ThreadLocalRandom rnd, int realX, int realY) {
        ArrayList<Map<String, Integer>> fakeGaps = new ArrayList<Map<String, Integer>>();
        int minDist = 60;
        for (int i = 0; i < 2; ++i) {
            int attempts = 0;
            int[] pos = new int[]{rnd.nextInt(50, 221), rnd.nextInt(30, 121)};
            while (++attempts < 30 && this.isTooCloseToRealGap(pos[0], pos[1], realX, realY, minDist)) {
            }
            int x = pos[0];
            int y = pos[1];
            boolean overlaps = fakeGaps.stream().anyMatch(gap -> {
                int gx = (Integer)gap.get("x");
                int gy = (Integer)gap.get("y");
                return Math.abs(x - gx) < 40 && Math.abs(y - gy) < 40;
            });
            if (overlaps) continue;
            fakeGaps.add(Map.of("x", x, "y", y, "width", 50, "height", 50));
        }
        return fakeGaps;
    }

    private boolean isTooCloseToRealGap(int x, int y, int realX, int realY, int minDist) {
        double dist = Math.hypot(x - realX, y - realY);
        return dist < (double)minDist;
    }

    public boolean verifyCaptcha(String token, int x, int y) {
        String key = SliderCaptchaService.getKey(token);
        Map data = this.redis.opsForHash().entries((Object)key);
        if (data.isEmpty()) {
            log.warn("slider captcha verification failed: token={} reason=NOT_FOUND", (Object)token);
            return false;
        }
        int targetX = Integer.parseInt((String)data.get("targetX"));
        int targetY = Integer.parseInt((String)data.get("targetY"));
        int deltaX = Math.abs(x - targetX);
        int deltaY = Math.abs(y - targetY);
        if (deltaX > 5 || deltaY > 5) {
            log.warn("slider captcha verification failed: token={} deltaX={} deltaY={}", new String[]{token, deltaX, deltaY});
            return false;
        }
        this.redis.delete(key);
        log.debug("slider captcha verification passed: token={}", (Object)token);
        return true;
    }

    private static String getKey(String token) {
        return "slider_captcha:" + token;
    }




    public record SliderCaptchaInitResult(String token, int targetX, int targetY, int gapX, int gapY, int gapWidth, int gapHeight, List<Map<String, Integer>> fakeGaps, String background) {}
}
