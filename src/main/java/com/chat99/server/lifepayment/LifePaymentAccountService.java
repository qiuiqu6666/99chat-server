package com.chat99.server.lifepayment;

import com.chat99.server.lifepayment.LifePaymentEnums.ServiceType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LifePaymentAccountService {

    private final LifePaymentAccountRepository accountRepository;
    private final LifePaymentCatalogService catalogService;

    public LifePaymentAccountService(LifePaymentAccountRepository accountRepository,
                                     LifePaymentCatalogService catalogService) {
        this.accountRepository = accountRepository;
        this.catalogService = catalogService;
    }

    public Map<String, Object> profile(String serviceTypeRaw, String accountNoRaw,
                                       String cityCode, String providerCode) {
        catalogService.ensureEnabled();
        ServiceType serviceType = ServiceType.require(serviceTypeRaw);
        if (accountNoRaw == null || accountNoRaw.isBlank()) {
            throw LifePaymentExceptions.badRequest("account_no_required");
        }
        String accountNo = serviceType == ServiceType.mobile
            ? LifePaymentSupport.normalizePhone(accountNoRaw)
            : accountNoRaw.trim();
        String city = LifePaymentSupport.blankToEmpty(cityCode);
        String provider = LifePaymentSupport.blankToEmpty(providerCode);

        Optional<LifePaymentAccount> found = serviceType == ServiceType.mobile
            ? accountRepository.findByServiceTypeAndAccountNoAndCityCodeAndProviderCode(
                serviceType, accountNo, "", "")
            : accountRepository.findByServiceTypeAndAccountNoAndCityCodeAndProviderCode(
                serviceType, accountNo, city, provider)
                .or(() -> accountRepository.findFirstByServiceTypeAndAccountNoOrderByUpdatedAtDesc(serviceType, accountNo));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("service_type", serviceType.name());
        resp.put("account_no", accountNo);
        if (found.isPresent()) {
            LifePaymentAccount account = found.get();
            resp.put("verified", account.isVerified());
            resp.put("success_count", account.getSuccessCount());
            resp.put("last_paid_at", LifePaymentSupport.formatTime(account.getLastPaidAt()));
            if (serviceType == ServiceType.mobile) {
                boolean needOwner = !account.isVerified();
                resp.put("need_owner_last_char", needOwner);
                resp.put("owner_last_char_exists",
                    account.getOwnerLastChar() != null && !account.getOwnerLastChar().isBlank());
            } else {
                resp.put("city_name", LifePaymentSupport.nullToEmpty(account.getCityName()));
                resp.put("city_code", LifePaymentSupport.nullToEmpty(account.getCityCode()));
                resp.put("provider_name", LifePaymentSupport.nullToEmpty(account.getProviderName()));
                resp.put("provider_code", LifePaymentSupport.nullToEmpty(account.getProviderCode()));
                resp.put("user_address", LifePaymentSupport.nullToEmpty(account.getUserAddress()));
            }
        } else {
            resp.put("verified", false);
            resp.put("success_count", 0);
            resp.put("last_paid_at", null);
            if (serviceType == ServiceType.mobile) {
                resp.put("need_owner_last_char", true);
                resp.put("owner_last_char_exists", false);
            } else {
                resp.put("city_name", "");
                resp.put("city_code", city);
                resp.put("provider_name", "");
                resp.put("provider_code", provider);
                resp.put("user_address", "");
            }
        }
        return resp;
    }

    public boolean isMobileVerified(String phone) {
        return accountRepository.findByServiceTypeAndAccountNoAndCityCodeAndProviderCode(
                ServiceType.mobile, phone, "", "")
            .map(LifePaymentAccount::isVerified)
            .orElse(false);
    }

    public Optional<LifePaymentAccount> findMobile(String phone) {
        return accountRepository.findByServiceTypeAndAccountNoAndCityCodeAndProviderCode(
            ServiceType.mobile, phone, "", "");
    }

    @Transactional
    public void markSuccess(ServiceType serviceType, String accountNo, String cityName, String cityCode,
                            String providerName, String providerCode, String ownerLastChar,
                            String userAddress, String orderNo) {
        String city = LifePaymentSupport.blankToEmpty(cityCode);
        String provider = LifePaymentSupport.blankToEmpty(providerCode);
        String account = serviceType == ServiceType.mobile
            ? LifePaymentSupport.normalizePhone(accountNo)
            : accountNo.trim();
        LifePaymentAccount entity = accountRepository
            .findByServiceTypeAndAccountNoAndCityCodeAndProviderCode(serviceType, account, city, provider)
            .orElseGet(() -> {
                LifePaymentAccount created = new LifePaymentAccount();
                created.setServiceType(serviceType);
                created.setAccountNo(account);
                created.setCityCode(city);
                created.setProviderCode(provider);
                return created;
            });
        if (cityName != null && !cityName.isBlank()) {
            entity.setCityName(cityName);
        }
        if (providerName != null && !providerName.isBlank()) {
            entity.setProviderName(providerName);
        }
        if (ownerLastChar != null && !ownerLastChar.isBlank()) {
            entity.setOwnerLastChar(ownerLastChar.trim());
        }
        if (userAddress != null && !userAddress.isBlank()) {
            entity.setUserAddress(userAddress);
        }
        Instant now = Instant.now();
        if (!entity.isVerified()) {
            entity.setVerified(true);
            entity.setFirstVerifiedAt(now);
        }
        entity.setSuccessCount(entity.getSuccessCount() + 1);
        entity.setLastPaidAt(now);
        entity.setLastOrderNo(orderNo);
        accountRepository.save(entity);
    }
}
