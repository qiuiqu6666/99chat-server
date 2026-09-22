package com.chat99.server.wallet;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletCurrencyCatalogEntryRepository extends JpaRepository<WalletCurrencyCatalogEntry, String> {

    List<WalletCurrencyCatalogEntry> findAllByOrderBySortOrderAsc();

    List<WalletCurrencyCatalogEntry> findByEnabledTrueOrderBySortOrderAsc();
}
