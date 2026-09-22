package com.chat99.server.lifepayment;

import com.chat99.server.lifepayment.LifePaymentEnums.ServiceType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LifePaymentCatalogService {

    private final LifePaymentProperties properties;
    private final LifePaymentAmountOptionRepository amountOptionRepository;
    private final LifePaymentProviderRepository providerRepository;

    public LifePaymentCatalogService(LifePaymentProperties properties,
                                     LifePaymentAmountOptionRepository amountOptionRepository,
                                     LifePaymentProviderRepository providerRepository) {
        this.properties = properties;
        this.amountOptionRepository = amountOptionRepository;
        this.providerRepository = providerRepository;
    }

    public Map<String, Object> services() {
        ensureEnabled();
        List<Map<String, Object>> items = new ArrayList<>();
        for (ServiceType type : ServiceType.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("service_type", type.name());
            item.put("enabled", properties.isEnabled());
            item.put("maintenance_message", properties.isEnabled() ? "" : "服务维护中");
            items.add(item);
        }
        return Map.of("items", items);
    }

    public Map<String, Object> mobileAmountOptions(String phoneRaw, String operatorName) {
        ensureEnabled();
        String phone = phoneRaw == null || phoneRaw.isBlank() ? "" : LifePaymentSupport.normalizePhone(phoneRaw);
        String carrier = operatorName == null || operatorName.isBlank()
            ? LifePaymentSupport.guessCarrier(phone)
            : operatorName.trim();
        List<LifePaymentAmountOption> options = amountOptionRepository.findMobileOptions(ServiceType.mobile, carrier);
        if (options.isEmpty()) {
            options = amountOptionRepository.findMobileOptions(ServiceType.mobile, null);
        }
        List<Map<String, Object>> items = options.stream().map(this::toAmountItem).toList();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("phone", phone);
        resp.put("operator_name", carrier);
        resp.put("items", items);
        return resp;
    }

    public Map<String, Object> utilityAmountOptions(String serviceTypeRaw, String cityCode, String providerCode) {
        ensureEnabled();
        ServiceType serviceType = ServiceType.requireUtility(serviceTypeRaw);
        List<LifePaymentAmountOption> options =
            amountOptionRepository.findUtilityOptions(serviceType, cityCode, providerCode);
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> items = new ArrayList<>();
        for (LifePaymentAmountOption option : options) {
            String key = LifePaymentSupport.formatAmount(option.getAmount());
            if (seen.add(key)) {
                items.add(toAmountItem(option));
            }
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("service_type", serviceType.name());
        resp.put("items", items);
        resp.put("allow_custom_amount", true);
        return resp;
    }

    public Map<String, Object> providers(String serviceTypeRaw, String cityName, String cityCode,
                                         String keyword, int page, int pageSize) {
        ensureEnabled();
        ServiceType serviceType = ServiceType.requireUtility(serviceTypeRaw);
        int p = Math.max(page, 1);
        int size = Math.min(Math.max(pageSize, 1), 100);
        Page<LifePaymentProvider> result = providerRepository.search(
            serviceType,
            LifePaymentSupport.blankToEmpty(cityCode),
            LifePaymentSupport.blankToEmpty(cityName),
            LifePaymentSupport.blankToEmpty(keyword),
            PageRequest.of(p - 1, size));
        List<Map<String, Object>> items = result.getContent().stream().map(provider -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("service_type", provider.getServiceType().name());
            item.put("city_name", provider.getCityName());
            item.put("city_code", LifePaymentSupport.nullToEmpty(provider.getCityCode()));
            item.put("provider_name", provider.getProviderName());
            item.put("provider_code", provider.getProviderCode());
            item.put("enabled", provider.isEnabled());
            return item;
        }).toList();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        resp.put("page", p);
        resp.put("page_size", size);
        resp.put("total", result.getTotalElements());
        return resp;
    }

    @Transactional
    public void ensureSeedAmounts() {
        if (amountOptionRepository.count() > 0) {
            return;
        }
        seedAmount(ServiceType.mobile, null, null, "30.00", 10);
        seedAmount(ServiceType.mobile, null, null, "50.00", 20);
        seedAmount(ServiceType.mobile, null, null, "100.00", 30);
        seedAmount(ServiceType.mobile, null, null, "200.00", 40);
        for (ServiceType type : List.of(ServiceType.water, ServiceType.electric, ServiceType.gas)) {
            seedAmount(type, null, null, "20.00", 10);
            seedAmount(type, null, null, "50.00", 20);
            seedAmount(type, null, null, "100.00", 30);
        }
    }

    private void seedAmount(ServiceType type, String cityCode, String providerCode, String amount, int sort) {
        LifePaymentAmountOption option = new LifePaymentAmountOption();
        option.setServiceType(type);
        option.setCityCode(cityCode);
        option.setProviderCode(providerCode);
        option.setAmount(new BigDecimal(amount));
        option.setSortOrder(sort);
        amountOptionRepository.save(option);
    }

    private Map<String, Object> toAmountItem(LifePaymentAmountOption option) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("amount", option.getAmount());
        item.put("label", LifePaymentSupport.amountLabel(option.getAmount()));
        item.put("enabled", option.isEnabled());
        return item;
    }

    void ensureEnabled() {
        if (!properties.isEnabled()) {
            throw LifePaymentExceptions.of(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "service_disabled");
        }
    }
}
