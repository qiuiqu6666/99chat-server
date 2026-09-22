package com.chat99.server.adminapi;

import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminAccountRepository extends JpaRepository<AdminAccount, Long> {
    Optional<AdminAccount> findByUsername(String username);

    java.util.List<AdminAccount> findByUsernameIn(Collection<String> usernames);
}
