package com.chat99.server.official;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OfficialAccountRepository extends JpaRepository<OfficialAccount, Long> {

    Optional<OfficialAccount> findByOfficialAccountId(String officialAccountId);

    Optional<OfficialAccount> findBySlug(String slug);

    List<OfficialAccount> findByEnabledTrueOrderBySortOrderAscIdAsc();

    List<OfficialAccount> findAllByOrderBySortOrderAscIdAsc();
}
