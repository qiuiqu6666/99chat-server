package com.chat99.sangong.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    /** 已下线：玩家鉴权直接使用主服务登录颁发的 JWT，无需再换三公自有 Token。 */
    @PostMapping("/api/v1/auth/token")
    public ResponseEntity<Map<String, Object>> token() {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("ok", false);
        err.put("code", "ENDPOINT_GONE");
        err.put("message", "请直接使用主服务登录 Token（Authorization: Bearer <主服务JWT>）");
        return ResponseEntity.status(410).body(err);
    }
}
