package com.chat99.server.lifepayment;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LifePaymentMobileDetailRepository extends JpaRepository<LifePaymentMobileDetail, Long> {
    Optional<LifePaymentMobileDetail> findByOrderNo(String orderNo);
}
