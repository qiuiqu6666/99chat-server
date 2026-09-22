package com.chat99.server.admin;

import com.chat99.server.common.AppSettingService;
import com.chat99.server.oss.OssClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/admin/settings")
public class SettingsAdminController {

    private static final Set<String> KNOWN_KEYS = Set.of(
        AppSettingService.JWT_SECRET,
        AppSettingService.IM_SDK_APP_ID,
        AppSettingService.IM_KEY,
        AppSettingService.SMSBAO_USER,
        AppSettingService.SMSBAO_PWD_MD5,
        AppSettingService.OSS_ENDPOINT,
        AppSettingService.OSS_BUCKET,
        AppSettingService.OSS_ACCESS_KEY_ID,
        AppSettingService.OSS_ACCESS_KEY_SECRET,
        AppSettingService.OSS_CDN_DOMAIN);

    private static final Set<String> SECRET_KEYS = Set.of(
        AppSettingService.JWT_SECRET,
        AppSettingService.IM_KEY,
        AppSettingService.SMSBAO_PWD_MD5,
        AppSettingService.OSS_ACCESS_KEY_SECRET);

    private final AdminGuard guard;
    private final AppSettingService settings;
    private final OssClient ossClient;

    public SettingsAdminController(AdminGuard guard, AppSettingService settings, OssClient ossClient) {
        this.guard = guard;
        this.settings = settings;
        this.ossClient = ossClient;
    }

    public record SettingValue(@NotBlank String value) {}

    @GetMapping
    public Map<String, Object> list(HttpServletRequest http) {
        guard.check(http);
        Map<String, String> all = settings.snapshot();
        Map<String, Object> view = new LinkedHashMap<>();
        for (String key : KNOWN_KEYS) {
            String v = all.get(key);
            view.put(key, Map.of(
                "set", v != null && !v.isBlank(),
                "preview", mask(key, v)));
        }
        return view;
    }

    @PutMapping("/{key}")
    public Map<String, Object> set(@PathVariable String key,
                                   @RequestBody SettingValue body,
                                   HttpServletRequest http) {
        guard.check(http);
        if (!KNOWN_KEYS.contains(key)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNKNOWN_KEY");
        }
        if (AppSettingService.JWT_SECRET.equals(key) && body.value().length() < 32) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "JWT_SECRET_TOO_SHORT");
        }
        settings.set(key, body.value());
        return Map.of("key", key, "preview", mask(key, body.value()));
    }

    @PostMapping("/reload")
    public Map<String, Object> reload(HttpServletRequest http) {
        guard.check(http);
        settings.reload();
        ossClient.invalidate();
        return Map.of("reloaded", settings.snapshot().size());
    }

    private String mask(String key, String value) {
        if (value == null || value.isBlank()) return "";
        if (!SECRET_KEYS.contains(key)) return value;
        int n = value.length();
        if (n <= 6) return "***";
        return value.substring(0, 3) + "***" + value.substring(n - 3);
    }
}
