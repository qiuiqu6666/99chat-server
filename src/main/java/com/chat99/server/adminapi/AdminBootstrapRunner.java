package com.chat99.server.adminapi;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "chat99.admin-api", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);
    private static final List<String> ENSURE_PERMISSIONS = List.of(
        "group.read",
        "group.write",
        "dashboard.view",
        "wallet.read",
        "user.read",
        "user.write",
        "admin.manage",
        "system.config");

    private final AdminAccountRepository repository;

    public AdminBootstrapRunner(AdminAccountRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (AdminAccount account : repository.findAll()) {
            ensurePermissions(account);
        }
    }

    private void ensurePermissions(AdminAccount account) {
        Set<String> permissions = new LinkedHashSet<>();
        if (account.getPermissionsCsv() != null && !account.getPermissionsCsv().isBlank()) {
            permissions.addAll(Arrays.stream(account.getPermissionsCsv().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList());
        }
        int before = permissions.size();
        permissions.addAll(ENSURE_PERMISSIONS);
        if (permissions.size() == before) {
            return;
        }
        account.setPermissionsCsv(permissions.stream().collect(Collectors.joining(",")));
        repository.save(account);
        log.info("Updated admin permissions username={}", account.getUsername());
    }
}
