package com.chat99.server.adminapi;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/geoip")
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AdminGeoIpController {

    private final AdminGeoIpService geoIp;

    public AdminGeoIpController(AdminGeoIpService geoIp) {
        this.geoIp = geoIp;
    }

    @GetMapping
    public GeoIpResponse lookup(Authentication auth, @RequestParam String ip) {
        AdminAccess.requirePermission(auth, "user.read");
        geoIp.validateLookupIp(ip);
        return new GeoIpResponse(ip.trim(), geoIp.lookupRegion(ip));
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GeoIpResponse(String ip, String region) {}
}
