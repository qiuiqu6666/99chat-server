package com.chat99.server.lifepayment;

import com.chat99.server.lifepayment.LifePaymentEnums.ServiceType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LifePaymentAmountOptionRepository extends JpaRepository<LifePaymentAmountOption, Long> {

    @Query("""
        SELECT a FROM LifePaymentAmountOption a
        WHERE a.serviceType = :serviceType
          AND a.enabled = true
          AND (:operatorName IS NULL OR :operatorName = '' OR a.operatorName IS NULL OR a.operatorName = :operatorName)
        ORDER BY a.sortOrder ASC, a.amount ASC
        """)
    List<LifePaymentAmountOption> findMobileOptions(@Param("serviceType") ServiceType serviceType,
                                                    @Param("operatorName") String operatorName);

    @Query("""
        SELECT a FROM LifePaymentAmountOption a
        WHERE a.serviceType = :serviceType
          AND a.enabled = true
          AND (
            (a.providerCode IS NOT NULL AND :providerCode IS NOT NULL AND a.providerCode = :providerCode)
            OR (a.cityCode IS NOT NULL AND :cityCode IS NOT NULL AND a.cityCode = :cityCode AND a.providerCode IS NULL)
            OR (a.cityCode IS NULL AND a.providerCode IS NULL)
          )
        ORDER BY
          CASE
            WHEN a.providerCode IS NOT NULL AND a.providerCode = :providerCode THEN 0
            WHEN a.cityCode IS NOT NULL AND a.cityCode = :cityCode THEN 1
            ELSE 2
          END,
          a.sortOrder ASC, a.amount ASC
        """)
    List<LifePaymentAmountOption> findUtilityOptions(@Param("serviceType") ServiceType serviceType,
                                                     @Param("cityCode") String cityCode,
                                                     @Param("providerCode") String providerCode);
}
