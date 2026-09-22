package com.chat99.server.lifepayment;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LifePaymentUtilityDetailRepository extends JpaRepository<LifePaymentUtilityDetail, Long> {
    Optional<LifePaymentUtilityDetail> findByOrderNo(String orderNo);
}
