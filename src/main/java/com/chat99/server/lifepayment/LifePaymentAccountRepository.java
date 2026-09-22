package com.chat99.server.lifepayment;

import com.chat99.server.lifepayment.LifePaymentEnums.ServiceType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LifePaymentAccountRepository extends JpaRepository<LifePaymentAccount, Long> {
    Optional<LifePaymentAccount> findByServiceTypeAndAccountNoAndCityCodeAndProviderCode(
        ServiceType serviceType, String accountNo, String cityCode, String providerCode);

    Optional<LifePaymentAccount> findFirstByServiceTypeAndAccountNoOrderByUpdatedAtDesc(
        ServiceType serviceType, String accountNo);
}
