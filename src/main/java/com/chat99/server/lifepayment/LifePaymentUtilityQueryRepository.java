package com.chat99.server.lifepayment;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LifePaymentUtilityQueryRepository extends JpaRepository<LifePaymentUtilityQuery, Long> {
    Optional<LifePaymentUtilityQuery> findByQueryNo(String queryNo);

    Optional<LifePaymentUtilityQuery> findByUserIdAndQueryNo(String userId, String queryNo);
}
