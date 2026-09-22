package com.chat99.server.user;

import com.chat99.server.common.ClientContext;
import com.chat99.server.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PhoneChangeController {

    private final PhoneChangeService service;
    private final ClientContext clientContext;
    private final JwtService jwtService;

    public PhoneChangeController(PhoneChangeService service, ClientContext clientContext,
                                 JwtService jwtService) {
        this.service = service;
        this.clientContext = clientContext;
        this.jwtService = jwtService;
    }

    public record VerifyOldRequest(@NotBlank String changeId, @NotBlank String smsCode) {}

    public record SendNewRequest(@NotBlank String changeId,
                                 @NotBlank String newPhone,
                                 String phoneCountry) {}

    public record ConfirmRequest(@NotBlank String changeId, @NotBlank String smsCode) {}

    @PostMapping("/me/phone/change/start")
    public PhoneChangeService.StartResult start(Authentication auth, HttpServletRequest http) {
        String userId = (String) auth.getPrincipal();
        return service.start(userId, clientContext.ip(http));
    }

    @PostMapping("/me/phone/change/verify-old")
    public PhoneChangeService.SessionResult verifyOld(Authentication auth,
                                                      @Valid @RequestBody VerifyOldRequest req) {
        String userId = (String) auth.getPrincipal();
        return service.verifyOld(userId, req.changeId(), req.smsCode());
    }

    @PostMapping("/me/phone/change/send-new")
    public PhoneChangeService.SendNewResult sendNew(Authentication auth,
                                                    @Valid @RequestBody SendNewRequest req,
                                                    HttpServletRequest http) {
        String userId = (String) auth.getPrincipal();
        return service.sendNew(userId, req.changeId(), req.newPhone(), req.phoneCountry(),
            clientContext.ip(http));
    }

    @PostMapping("/me/phone/change/confirm")
    public PhoneChangeService.ConfirmResult confirm(Authentication auth,
                                                    @Valid @RequestBody ConfirmRequest req,
                                                    HttpServletRequest http) {
        String userId = (String) auth.getPrincipal();
        String currentDeviceId = currentDeviceId(http).orElse("");
        return service.confirm(userId, req.changeId(), req.smsCode(), currentDeviceId);
    }

    private java.util.Optional<String> currentDeviceId(HttpServletRequest http) {
        String header = http.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return java.util.Optional.empty();
        }
        return jwtService.parseDeviceId(header.substring(7));
    }
}
