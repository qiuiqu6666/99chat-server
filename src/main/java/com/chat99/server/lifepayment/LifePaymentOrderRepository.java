package com.chat99.server.lifepayment;

import com.chat99.server.lifepayment.LifePaymentEnums.OrderStatus;
import com.chat99.server.lifepayment.LifePaymentEnums.ServiceType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LifePaymentOrderRepository extends JpaRepository<LifePaymentOrder, Long> {
    Optional<LifePaymentOrder> findByOrderNo(String orderNo);

    Optional<LifePaymentOrder> findByUserIdAndClientOrderId(String userId, String clientOrderId);

    Optional<LifePaymentOrder> findByUserIdAndOrderNo(String userId, String orderNo);

    List<LifePaymentOrder> findTop10ByUserIdOrderByCreatedAtDesc(String userId);

    @Query("""
        SELECT COALESCE(SUM(o.amount), 0) FROM LifePaymentOrder o
        WHERE o.userId = :userId
          AND o.paidAt >= :monthStart
          AND o.orderStatus NOT IN :excluded
        """)
    java.math.BigDecimal sumMonthPaid(@Param("userId") String userId,
                                      @Param("monthStart") Instant monthStart,
                                      @Param("excluded") List<OrderStatus> excluded);

    @Query("""
        SELECT o FROM LifePaymentOrder o
        WHERE o.userId = :userId
          AND (:serviceType IS NULL OR o.serviceType = :serviceType)
          AND (:orderStatus IS NULL OR o.orderStatus = :orderStatus)
        ORDER BY o.createdAt DESC
        """)
    Page<LifePaymentOrder> search(@Param("userId") String userId,
                                  @Param("serviceType") ServiceType serviceType,
                                  @Param("orderStatus") OrderStatus orderStatus,
                                  Pageable pageable);

    @Query("""
        SELECT o FROM LifePaymentOrder o
        WHERE (:userId IS NULL OR :userId = '' OR o.userId = :userId)
          AND (:orderNo IS NULL OR :orderNo = '' OR o.orderNo = :orderNo)
          AND (:serviceType IS NULL OR o.serviceType = :serviceType)
          AND (:orderStatus IS NULL OR o.orderStatus = :orderStatus)
        ORDER BY o.createdAt DESC
        """)
    Page<LifePaymentOrder> adminSearch(@Param("userId") String userId,
                                       @Param("orderNo") String orderNo,
                                       @Param("serviceType") ServiceType serviceType,
                                       @Param("orderStatus") OrderStatus orderStatus,
                                       Pageable pageable);

    @Query("""
        SELECT o FROM LifePaymentOrder o
        WHERE o.serviceType IN :types
          AND o.orderStatus IN :statuses
        ORDER BY o.updatedAt ASC
        """)
    List<LifePaymentOrder> findPendingYuanren(@Param("types") List<ServiceType> types,
                                              @Param("statuses") List<OrderStatus> statuses,
                                              Pageable pageable);
}
