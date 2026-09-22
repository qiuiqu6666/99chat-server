package com.chat99.server.lifepayment;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LifePaymentOperationLogRepository extends JpaRepository<LifePaymentOperationLog, Long> {
    List<LifePaymentOperationLog> findByOrderNoOrderByCreatedAtAsc(String orderNo);
}
