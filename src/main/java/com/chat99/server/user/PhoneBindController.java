package com.chat99.server.user;

import com.chat99.server.common.ClientContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PhoneBindController {

    private final PhoneBindService service;
    private final ClientContext clientContext;

    public PhoneBindController(PhoneBindService service, ClientContext clientContext) {
        this.service = service;
        this.clientContext = clientContext;
    }

    public record StartRequest(@NotBlank String phone, String phoneCountry) {}

    public record ConfirmRequest(@NotBlank String bindId, @NotBlank String smsCode) {}

    @PostMapping("/me/phone/bind/start")
    public PhoneBindService.StartResult start(Authentication auth,
                                              @Valid @RequestBody StartRequest req,
                                              HttpServletRequest http) {
        String userId = (String) auth.getPrincipal();
        return service.start(userId, req.phone(), req.phoneCountry(), clientContext.ip(http));
    }

    @PostMapping("/me/phone/bind/confirm")
    public PhoneBindService.ConfirmResult confirm(Authentication auth,
                                                  @Valid @RequestBody ConfirmRequest req) {
        String userId = (String) auth.getPrincipal();
        return service.confirm(userId, req.bindId(), req.smsCode());
    }
}
