package com.chat99.server.admin;

import com.chat99.server.common.ClientContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AdminGuard {

    private final Set<String> whitelist;
    private final ClientContext clientContext;

    public AdminGuard(AdminProperties props, ClientContext clientContext) {
        this.clientContext = clientContext;
        String csv = props.ipWhitelist() == null ? "" : props.ipWhitelist();
        this.whitelist = new HashSet<>(Arrays.asList(csv.split(",")));
    }

    public void check(HttpServletRequest http) {
        String ip = clientContext.ip(http);
        if (ip == null || !whitelist.contains(ip.trim())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ADMIN_FORBIDDEN");
        }
    }
}
