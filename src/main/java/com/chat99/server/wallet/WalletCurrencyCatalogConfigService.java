package com.chat99.server.wallet;

import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WalletCurrencyCatalogConfigService {

    private static final Logger log = LoggerFactory.getLogger(WalletCurrencyCatalogConfigService.class);

    private final WalletCurrencyCatalogProperties defaultProps;
    private final WalletCurrencyCatalogEntryRepository repository;

    public WalletCurrencyCatalogConfigService(WalletCurrencyCatalogProperties defaultProps,
                                              WalletCurrencyCatalogEntryRepository repository) {
        this.defaultProps = defaultProps;
        this.repository = repository;
    }

    @EventListener(ApplicationReadyEvent.class)
    void seedOnStartup() {
        ensureSeeded();
    }

    @Transactional
    public void ensureSeeded() {
        if (repository.count() > 0) {
            return;
        }
        List<CurrencyCatalogItem> defaults = defaultProps.items();
        defaults.stream()
            .sorted(Comparator.comparingInt(CurrencyCatalogItem::sortOrder))
            .forEach(item -> repository.save(fromItem(item)));
        log.info("Seeded wallet currency catalog with {} default items", defaults.size());
    }

    public List<CurrencyCatalogItem> listEnabledItems() {
        ensureSeeded();
        return repository.findByEnabledTrueOrderBySortOrderAsc().stream()
            .map(this::toItem)
            .toList();
    }

    public List<CurrencyCatalogItem> listAllItems() {
        ensureSeeded();
        return repository.findAllByOrderBySortOrderAsc().stream()
            .map(this::toItem)
            .toList();
    }

    public List<WalletCurrencyCatalogEntry> listAllEntries() {
        ensureSeeded();
        return repository.findAllByOrderBySortOrderAsc();
    }

    public WalletCurrencyCatalogEntry requireEntry(String code) {
        ensureSeeded();
        return repository.findById(normalizeCode(code))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "CURRENCY_NOT_FOUND"));
    }

    @Transactional
    public WalletCurrencyCatalogEntry update(String code, UpdateCommand cmd) {
        WalletCurrencyCatalogEntry entry = requireEntry(code);
        if (cmd.name() != null) {
            String name = cmd.name().trim();
            if (name.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_NAME");
            }
            entry.setName(name);
        }
        if (cmd.logoUrl() != null) {
            entry.setLogoUrl(trimToNull(cmd.logoUrl()));
        }
        if (cmd.platformCoin() != null) {
            entry.setPlatformCoin(cmd.platformCoin());
        }
        if (cmd.depositEnabled() != null) {
            entry.setDepositEnabled(cmd.depositEnabled());
        }
        if (cmd.withdrawEnabled() != null) {
            entry.setWithdrawEnabled(cmd.withdrawEnabled());
        }
        if (cmd.sortOrder() != null) {
            entry.setSortOrder(cmd.sortOrder());
        }
        if (cmd.enabled() != null) {
            entry.setEnabled(cmd.enabled());
        }
        return repository.save(entry);
    }

    public record UpdateCommand(
        String name,
        String logoUrl,
        Boolean platformCoin,
        Boolean depositEnabled,
        Boolean withdrawEnabled,
        Integer sortOrder,
        Boolean enabled) {}

    private static WalletCurrencyCatalogEntry fromItem(CurrencyCatalogItem item) {
        WalletCurrencyCatalogEntry entry = new WalletCurrencyCatalogEntry();
        entry.setCode(normalizeCode(item.code()));
        entry.setName(item.name());
        entry.setLogoUrl(item.logoUrl());
        entry.setPlatformCoin(item.platformCoin());
        entry.setDepositEnabled(item.depositEnabled());
        entry.setWithdrawEnabled(item.withdrawEnabled());
        entry.setSortOrder(item.sortOrder());
        entry.setEnabled(true);
        return entry;
    }

    private CurrencyCatalogItem toItem(WalletCurrencyCatalogEntry entry) {
        return new CurrencyCatalogItem(
            entry.getCode(),
            entry.getName(),
            entry.getLogoUrl(),
            entry.isPlatformCoin(),
            entry.isDepositEnabled(),
            entry.isWithdrawEnabled(),
            entry.getSortOrder());
    }

    private static String normalizeCode(String code) {
        if (code == null || code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_CODE");
        }
        return code.trim().toUpperCase();
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
